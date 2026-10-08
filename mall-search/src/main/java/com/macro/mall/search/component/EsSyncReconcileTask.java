package com.macro.mall.search.component;

import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.repository.EsProductRepository;
import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 商品索引的定时对账任务 —— "拉取负责正确性"这条腿。
 *
 * ==================== 为什么有了 MQ 推送还需要对账 ====================
 * 推送通道解决的是**实时性**，它建立在三个不可能同时成立的假设上：
 *   ① 消息不丢（网络抖动、Broker 重启、磁盘满都会丢）
 *   ② 消费必成（ES 短暂不可用、索引写满都会失败）
 *   ③ 事件全覆盖（将来新增一个直接改库的脚本/后台任务，它就绕过了所有通知点）
 *
 * 只要有任意一条不成立，索引就会和数据库产生**永久性偏差** ——
 * 而且推送模型**发现不了自己的偏差**：它只知道自己处理过的消息，
 * 不知道"本来应该还有一条消息没来"。
 *
 * 对账则完全不同：它不依赖任何事件，直接比对两个存储的最终状态。
 * **推送 + 对账 = 实时性 + 正确性，两者缺一不可。**
 * 这和支付链路的思路一致：异步回调（推送）负责及时性，
 * 主动查询 /alipay/query（拉取）负责兜底。
 *
 * ==================== 对账策略：全量 id 差集 ====================
 * 比对两个集合：
 *   · DB 应该有、ES 没有  → 漏同步，补进索引
 *   · ES 有、DB 不应该有  → 已下架/已删除但没清掉，从索引移除（**这是"下架还能搜到"的最终兜底**）
 *
 * 【已知的规模边界】这里用 findAll() 把 ES 的文档全捞出来取 id。
 * 在项目这个量级（千级商品）完全够用，但**不适合百万级**：
 *   · ES 侧应改用 scroll / search_after 分批取 id（只取 _source=false，不要整行）
 *   · DB 侧应改为按 id 分段比对，避免一次拉全表
 * 之所以现在不这么做，是因为"分段比对"会引入"游标推进时两边快照不一致"的新问题，
 * 需要配合快照版本号才能正确处理 —— 那是另一个量级的复杂度，
 * 在千级数据下属于过度设计。**知道边界在哪、并且说清楚，比提前优化更重要。**
 *
 * Created 2026/10/08.
 */
@Component
// 用配置开关控制：默认开启；排查问题时可以关掉，避免定时任务干扰观察
@ConditionalOnProperty(name = "mall.es-sync.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class EsSyncReconcileTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(EsSyncReconcileTask.class);

    /** 单次对账最多修复多少个商品，避免一次跑太久把 ES 打满 */
    private static final int MAX_FIX_PER_ROUND = 500;

    @Autowired
    private EsProductDao productDao;
    @Autowired
    private EsProductRepository productRepository;
    @Autowired
    private EsProductService esProductService;

    /**
     * 定时对账入口。
     *
     * 【为什么整个方法包一层 try-catch】
     * 定时任务抛异常不会自动重试，而且**未捕获的异常在某些调度配置下会让该任务后续不再触发**。
     * 索引对账失败不是致命问题（下一轮还会再跑），所以吞掉异常 + 打 ERROR 是正确选择 ——
     * 但绝不能静默，必须留下可检索的日志。
     */
    @Scheduled(cron = "${mall.es-sync.reconcile.cron:0 */10 * * * ?}")
    public void reconcile() {
        try {
            long start = System.currentTimeMillis();
            Set<Long> expected = new HashSet<>(productDao.getPublishedProductIds());
            Set<Long> actual = loadIndexedProductIds();

            List<Long> missing = expected.stream()
                    .filter(id -> !actual.contains(id))
                    .limit(MAX_FIX_PER_ROUND)
                    .collect(Collectors.toList());
            List<Long> stale = actual.stream()
                    .filter(id -> !expected.contains(id))
                    .limit(MAX_FIX_PER_ROUND)
                    .collect(Collectors.toList());

            if (missing.isEmpty() && stale.isEmpty()) {
                LOGGER.debug("商品索引对账一致：DB={} ES={} 耗时={}ms",
                        expected.size(), actual.size(), System.currentTimeMillis() - start);
                return;
            }

            LOGGER.warn("★ 商品索引对账发现差异：漏同步 {} 个、应删除 {} 个（DB={} ES={}）",
                    missing.size(), stale.size(), expected.size(), actual.size());

            // 漏同步的：走统一的收敛逻辑补进去
            if (!missing.isEmpty()) {
                esProductService.syncProducts(missing);
                LOGGER.warn("已补同步 {} 个商品：{}", missing.size(),
                        missing.size() > 20 ? missing.subList(0, 20) + "..." : missing);
            }
            // 多出来的：这些是"已下架/已删除但索引里还留着"的，必须移除
            if (!stale.isEmpty()) {
                for (Long id : stale) {
                    try {
                        productRepository.deleteById(id);
                    } catch (Exception e) {
                        LOGGER.error("移除多余索引文档失败：id={}", id, e);
                    }
                }
                LOGGER.warn("已从索引移除 {} 个不该存在的商品：{}", stale.size(),
                        stale.size() > 20 ? stale.subList(0, 20) + "..." : stale);
            }

            if (missing.size() == MAX_FIX_PER_ROUND || stale.size() == MAX_FIX_PER_ROUND) {
                LOGGER.warn("本轮达到单次修复上限（{}），剩余差异将在下一轮继续处理", MAX_FIX_PER_ROUND);
            }
        } catch (Exception e) {
            // 不吞成静默：定时任务的失败必须有痕迹，否则"对账一直没生效"这件事没人会发现
            LOGGER.error("★ 商品索引对账任务执行失败（下一轮会重试）", e);
        }
    }

    /**
     * 取 ES 里实际存在的商品 id 集合。
     */
    private Set<Long> loadIndexedProductIds() {
        Iterable<EsProduct> all = productRepository.findAll();
        List<Long> ids = new ArrayList<>();
        for (EsProduct p : all) {
            if (p.getId() != null) {
                ids.add(p.getId());
            }
        }
        return new HashSet<>(ids);
    }
}
