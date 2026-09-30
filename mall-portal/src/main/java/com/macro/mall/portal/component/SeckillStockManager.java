package com.macro.mall.portal.component;

import com.macro.mall.portal.dao.SmsFlashPromotionDao;
import com.macro.mall.portal.domain.FlashPromotionStockInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 秒杀库存管理器：Redis 侧的预热、原子预扣、回滚。
 *
 * ============================ 为什么库存要放 Redis ============================
 * 秒杀的特点是「瞬时并发极高、但真正成交极少」。如果每个请求都去数据库
 * 做一次 UPDATE，数据库连接池和行锁会被瞬间打满，绝大多数请求只是在排队等锁，
 * 最后拿到"库存不足"。Redis 单机可承受十万级 QPS，把「判库存 + 扣库存 + 判限购」
 * 合成一个 Lua 脚本原子执行，就能在 Redis 层把 99% 的无效请求挡掉，
 * 只让真正抢到的少数请求进入后续链路。
 *
 * ========================= 关键设计：三道防线，层层兜底 =========================
 *   第一道（Redis + Lua）：挡住绝大部分并发。优点是快，缺点是 Redis 不可靠
 *                          —— 可能宕机、可能被误刷、可能网络分区。
 *   第二道（DB 条件更新）：即使 Redis 的库存算错了，DB 侧的
 *                          `WHERE total - sold >= qty` 也会拦住超卖。这是最终权威。
 *   第三道（定时对账）：   Redis 与 DB 出现分歧时能被发现并自动修复。
 *
 * 只有第一道是「性能优化」，第二道才是「正确性保证」。
 * 很多秒杀方案只讲第一道，Redis 一挂就超卖 —— 这是必须区分清楚的。
 *
 * ============================== Redis Key 设计 ==============================
 *   mall:seckill:stock:{promotionId}:{sessionId}:{productId}    String  剩余库存
 *   mall:seckill:buyers:{promotionId}:{sessionId}:{productId}   Hash    memberId -> 已购数量
 *   mall:seckill:warm:{promotionId}:{sessionId}                 String  预热时间戳（毫秒）
 *
 * 为什么用 Hash 而不是 Set 记录已购用户：Set 只能表达「买过/没买过」，
 * 只能支持「每人限 1 件」。用 Hash 存「每人已购几件」，才能支持任意限购数量
 * （现场里 limit=2 的商品就是这种）。
 *
 * Created 2026/09/30.
 */
@Component
public class SeckillStockManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillStockManager.class);

    /** 秒杀相关 key 的统一前缀，便于运维按前缀清理 */
    public static final String KEY_PREFIX = "mall:seckill:";
    /** 库存 / 已购记录的有效期：给足 6 小时（覆盖最长场次 + 善后时间） */
    private static final long KEY_TTL_SECONDS = 6 * 60 * 60L;

    /* ---------------- Lua 返回值约定 ---------------- */
    public static final long RESULT_SUCCESS = 0L;
    public static final long RESULT_OUT_OF_STOCK = 1L;
    public static final long RESULT_LIMIT_EXCEEDED = 2L;
    public static final long RESULT_NOT_WARMED = 3L;

    /**
     * 预扣库存脚本。
     *
     * 为什么必须是 Lua：这四步（读库存 → 判限购 → 扣库存 → 记已购）如果分成四次
     * Redis 调用，中间任何一个时刻都可能被并发请求穿插进来，出现
     * 「两个请求都读到库存 1、都判定可买、都扣减」的超卖。
     * Redis 执行 Lua 是单线程原子的，四步合一个脚本就没有中间态。
     */
    private static final String LUA_DEDUCT = """
            local stockKey  = KEYS[1]
            local buyersKey = KEYS[2]
            local memberId  = ARGV[1]
            local qty       = tonumber(ARGV[2])
            local limit     = tonumber(ARGV[3])
            local ttl       = tonumber(ARGV[4])

            local stock = redis.call('GET', stockKey)
            if not stock then
                return 3
            end
            stock = tonumber(stock)

            local bought = tonumber(redis.call('HGET', buyersKey, memberId) or '0')
            if bought + qty > limit then
                return 2
            end

            if stock < qty then
                return 1
            end

            redis.call('DECRBY', stockKey, qty)
            redis.call('HINCRBY', buyersKey, memberId, qty)
            redis.call('EXPIRE', buyersKey, ttl)
            return 0
            """;

    /**
     * 回滚脚本：库存加回去、已购数量减回去。
     *
     * 两个必须处理的边界：
     *   ① 库存不能加超过总量（用 total 做上限）—— 否则一次重复回滚就会
     *      凭空多出库存，而下一次对账又会把它改回去，来回抖动；
     *   ② 已购数量减到 0 要删掉这个 field，否则 Hash 里会残留一堆 0，
     *      既占内存，又让「已购人数」这类统计失真。
     */
    private static final String LUA_RELEASE = """
            local stockKey  = KEYS[1]
            local buyersKey = KEYS[2]
            local memberId  = ARGV[1]
            local qty       = tonumber(ARGV[2])
            local total     = tonumber(ARGV[3])

            local stock = redis.call('GET', stockKey)
            if stock then
                local newVal = tonumber(stock) + qty
                if newVal > total then
                    newVal = total
                end
                redis.call('SET', stockKey, newVal, 'KEEPTTL')
            end

            local bought = tonumber(redis.call('HGET', buyersKey, memberId) or '0')
            if bought > 0 then
                local left = bought - qty
                if left > 0 then
                    redis.call('HSET', buyersKey, memberId, left)
                else
                    redis.call('HDEL', buyersKey, memberId)
                end
            end
            return 1
            """;

    private final DefaultRedisScript<Long> deductScript =
            new DefaultRedisScript<>(LUA_DEDUCT, Long.class);
    private final DefaultRedisScript<Long> releaseScript =
            new DefaultRedisScript<>(LUA_RELEASE, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private SmsFlashPromotionDao flashPromotionDao;

    /* ===================== Key 构造 ===================== */

    public String stockKey(Long promotionId, Long sessionId, Long productId) {
        return KEY_PREFIX + "stock:" + promotionId + ":" + sessionId + ":" + productId;
    }

    public String buyersKey(Long promotionId, Long sessionId, Long productId) {
        return KEY_PREFIX + "buyers:" + promotionId + ":" + sessionId + ":" + productId;
    }

    public String warmKey(Long promotionId, Long sessionId) {
        return KEY_PREFIX + "warm:" + promotionId + ":" + sessionId;
    }

    /* ===================== 预热 ===================== */

    /**
     * 把某个活动×场次的秒杀库存预热到 Redis。
     *
     * 【输入】promotionId + sessionId
     * 【输出】预热成功的商品数
     *
     * 【幂等与安全】这条链路最容易出错的地方是「预热把已售库存又写回去」，
     * 所以规则是：**以 DB 的 (总数 - 已售) 为准重建**。因为已售数量是落库的，
     * 它是最终权威；只要在途消息已经落库，重建就是安全的。
     * 为了让"在途消息未落库"的风险最小化，预热只在**场次开始前**由定时任务触发；
     * 手动预热接口带 force 参数，允许强制重建（此时会打告警日志，便于察觉异常操作）。
     *
     * @param force 为 false 时，若该场次已预热过则直接跳过（不覆盖）
     */
    public int warmUp(Long promotionId, Long sessionId, boolean force) {
        String warmKey = warmKey(promotionId, sessionId);
        Boolean warmed = stringRedisTemplate.hasKey(warmKey);
        if (Boolean.TRUE.equals(warmed) && !force) {
            LOGGER.info("秒杀场次已预热，跳过。promotionId={} sessionId={}", promotionId, sessionId);
            return 0;
        }
        if (Boolean.TRUE.equals(warmed)) {
            LOGGER.warn("秒杀场次被强制重新预热（会以 DB 已售数量为准重建 Redis 库存）。"
                    + "若此刻有消息尚未落库，可能短暂放大可售量，等待对账任务修正。"
                    + "promotionId={} sessionId={}", promotionId, sessionId);
        }

        List<FlashPromotionStockInfo> stockList =
                flashPromotionDao.selectStockInfoList(promotionId, sessionId);
        if (stockList.isEmpty()) {
            LOGGER.warn("秒杀场次下没有可预热的商品。promotionId={} sessionId={}", promotionId, sessionId);
            return 0;
        }

        int warmedCount = 0;
        for (FlashPromotionStockInfo info : stockList) {
            int remain = info.getRemainCount();
            if (remain < 0) {
                // 数据异常（已售 > 总数），不能直接写进 Redis，否则就是负数库存
                LOGGER.error("秒杀库存数据异常：已售({}) > 总数({})，跳过预热。relationId={}",
                        info.getFlashPromotionSoldCount(), info.getFlashPromotionCount(), info.getRelationId());
                continue;
            }
            String stockKey = stockKey(promotionId, sessionId, info.getProductId());
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(remain),
                    KEY_TTL_SECONDS, TimeUnit.SECONDS);
            // 已购记录按关系行重建：预热相当于"场次刚开始"，先把旧的清掉
            stringRedisTemplate.delete(buyersKey(promotionId, sessionId, info.getProductId()));
            warmedCount++;
        }
        stringRedisTemplate.opsForValue().set(warmKey, String.valueOf(System.currentTimeMillis()),
                KEY_TTL_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("秒杀库存预热完成：promotionId={} sessionId={} 商品数={}", promotionId, sessionId, warmedCount);
        return warmedCount;
    }

    /* ===================== 原子预扣 ===================== */

    /**
     * Redis 侧原子预扣库存。
     *
     * 【输入】活动/场次/商品、会员、数量、每人限购
     * 【输出】RESULT_SUCCESS / RESULT_OUT_OF_STOCK / RESULT_LIMIT_EXCEEDED / RESULT_NOT_WARMED
     *
     * 注意：**预扣成功不等于下单成功**。它只是"占了个座"，
     * 真正的订单要等 MQ 消费完才落库。所以任何一个环节失败都必须走 release 把座位还回去。
     */
    public long tryDeduct(Long promotionId, Long sessionId, Long productId,
                          Long memberId, int quantity, int limit) {
        Long code = stringRedisTemplate.execute(
                deductScript,
                Arrays.asList(stockKey(promotionId, sessionId, productId),
                        buyersKey(promotionId, sessionId, productId)),
                String.valueOf(memberId),
                String.valueOf(quantity),
                String.valueOf(limit),
                String.valueOf(KEY_TTL_SECONDS));
        return code == null ? RESULT_NOT_WARMED : code;
    }

    /**
     * 回滚预扣（取消订单 / 消费失败补偿）。
     *
     * 【为什么必须带 total 上限】重复回滚是常态（消息重投、用户重复取消），
     * 如果不设上限，一次多回滚就会让 Redis 库存超过真实总量，
     * 用户就能买到不存在的货。这和台账 #6「释放库存没有下限保护」是同一类问题，
     * 只是方向相反：那边是减成负数，这边是加成超量。
     */
    public void release(Long promotionId, Long sessionId, Long productId,
                        Long memberId, int quantity, int totalCount) {
        stringRedisTemplate.execute(
                releaseScript,
                Arrays.asList(stockKey(promotionId, sessionId, productId),
                        buyersKey(promotionId, sessionId, productId)),
                String.valueOf(memberId),
                String.valueOf(quantity),
                String.valueOf(totalCount));
        LOGGER.info("已回滚秒杀预扣：promotionId={} sessionId={} productId={} memberId={} qty={}",
                promotionId, sessionId, productId, memberId, quantity);
    }

    /* ===================== 查询（对账 / 展示用） ===================== */

    /** 读 Redis 侧剩余库存；返回 null 表示未预热 */
    public Integer getRedisStock(Long promotionId, Long sessionId, Long productId) {
        String value = stringRedisTemplate.opsForValue().get(stockKey(promotionId, sessionId, productId));
        return value == null ? null : Integer.valueOf(value);
    }

    /** 读某会员在某商品上的已购数量（Redis 侧） */
    public Integer getRedisBought(Long promotionId, Long sessionId, Long productId, Long memberId) {
        Object value = stringRedisTemplate.opsForHash()
                .get(buyersKey(promotionId, sessionId, productId), String.valueOf(memberId));
        return value == null ? 0 : Integer.valueOf(String.valueOf(value));
    }

    /** 读该场次下所有商品的 Redis 库存，便于对账时一次性比对 */
    public Map<Long, Integer> getRedisStockMap(Long promotionId, Long sessionId) {
        List<FlashPromotionStockInfo> list = flashPromotionDao.selectStockInfoList(promotionId, sessionId);
        return list.stream().collect(Collectors.toMap(
                FlashPromotionStockInfo::getProductId,
                info -> {
                    Integer v = getRedisStock(promotionId, sessionId, info.getProductId());
                    return v == null ? -1 : v;   // -1 表示该商品根本没预热
                }));
    }

    /**
     * 强制把 Redis 库存设为 DB 的权威值（对账任务发现分歧时调用）。
     */
    public void forceResetStock(Long promotionId, Long sessionId, Long productId, int remain) {
        stringRedisTemplate.opsForValue().set(
                stockKey(promotionId, sessionId, productId),
                String.valueOf(Math.max(remain, 0)),
                KEY_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 清理某场次的全部秒杀缓存（场次结束后调用，避免占内存） */
    public void clearSession(Long promotionId, Long sessionId) {
        List<FlashPromotionStockInfo> list = flashPromotionDao.selectStockInfoList(promotionId, sessionId);
        for (FlashPromotionStockInfo info : list) {
            stringRedisTemplate.delete(Collections.singletonList(
                    stockKey(promotionId, sessionId, info.getProductId())));
            stringRedisTemplate.delete(buyersKey(promotionId, sessionId, info.getProductId()));
        }
        stringRedisTemplate.delete(warmKey(promotionId, sessionId));
        LOGGER.info("已清理秒杀缓存：promotionId={} sessionId={}", promotionId, sessionId);
    }
}
