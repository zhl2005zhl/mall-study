package com.macro.mall.search.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 商品索引同步消息消费者。
 *
 * ==================== 消费端的核心：状态收敛，而不是执行指令 ====================
 * 消息里只有 id 列表，没有"该做什么"。收到消息后逐条调用
 * {@link EsProductService#syncProduct(Long)}，由它回查数据库决定
 * **"这个商品现在应该出现在索引里吗"**，然后 upsert 或 delete。
 *
 * 这样设计的好处（详见 ProductSyncMessage 的注释）：
 *   · 自愈 —— 任何一条消息到达都能纠正此前所有偏差，不依赖消息不丢不乱序
 *   · 幂等 —— 同一条消息消费 N 次结果一样
 *   · 判断逻辑只有一处 —— 不会出现"下架路径忘了删索引"这种漏分支的 bug
 *
 * ==================== 失败处理：抛出，交给重试与死信 ====================
 * 这里**不 try-catch**。消费失败时抛异常，让 Spring AMQP 的重试机制接管：
 * 按配置重试 3 次 → 仍失败则进死信队列 → 由对账任务兜底。
 * 如果在这里 catch 掉并正常返回，消息会被 ack，那条变更就**永远丢了**，
 * 而定时对账虽然能补救，但代价是"延迟到下一个对账周期"。
 * **能当场重试的，不要留给对账。**
 *
 * Created 2026/10/08.
 */
@Component
@RabbitListener(queues = "mall.product.sync")
public class ProductSyncReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSyncReceiver.class);

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EsProductService esProductService;

    @RabbitHandler
    public void handle(String json) {
        List<Long> productIds = parseProductIds(json);
        if (productIds == null || productIds.isEmpty()) {
            LOGGER.warn("商品索引同步消息为空，忽略。payload={}", json);
            return;
        }

        int ok = 0;
        for (Long id : productIds) {
            try {
                esProductService.syncProduct(id);
                ok++;
            } catch (Exception e) {
                // 单条失败不影响同批其它商品，但**必须抛出去** ——
                // 否则这条消息整体被误判为成功，失败的这些商品就漏同步了。
                LOGGER.error("商品索引同步失败：productId={}（同批 {} 条中第 {} 条）", id, productIds.size(), ok + 1, e);
                throw e;
            }
        }
        LOGGER.info("商品索引同步完成：本次处理 {} 个商品 {}", ok, productIds);
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseProductIds(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object ids = map.get("productIds");
            if (ids instanceof List<?> list) {
                return list.stream()
                        .map(v -> Long.valueOf(String.valueOf(v)))
                        .toList();
            }
            return null;
        } catch (Exception e) {
            // 解析不了的消息重试多少次都没用，直接抛 → 进死信队列，保留现场供排查
            LOGGER.error("商品索引同步消息解析失败：payload={}", json, e);
            throw new IllegalArgumentException("商品索引同步消息格式非法", e);
        }
    }
}
