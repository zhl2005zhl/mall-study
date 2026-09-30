package com.macro.mall.portal.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 秒杀限流器。
 *
 * ======================= 为什么限流要放在"扣库存之前" =======================
 * 秒杀的瞬时流量可能是真实成交量的成百上千倍。如果在扣库存之前不做任何拦截，
 * 每次「抢购」都要走一次 Redis 往返；限流能让同一用户的高频重复点击在
 * 客户端还没到 Redis 就被挡掉，直接省下这部分开销。
 *
 * 更重要的是：**防重复点击**。用户手抖点 20 次和脚本刷 1000 次，
 * 对业务来说都只想成交一次 —— 限流就是把这个意图变成硬约束。
 *
 * ============================= 两层限流 =============================
 *   第 1 层（用户维度）：同一个用户对同一商品，10 秒内最多 5 次。
 *                        挡住手抖和简单的脚本刷单。
 *   第 2 层（商品维度）：同一个商品全局每秒最多 N 次。
 *                        挡住"分布式肉鸡集体抢同一个爆款"这种绕开用户维度的攻击，
 *                        同时也是保护下游 MQ 和数据库不被打爆的闸门。
 *
 * 两层都要过。只做用户维度的话，攻击者用一万个账号就能绕开；
 * 只做商品维度的话，单个用户的疯狂点击仍然会制造大量无效 Redis 往返。
 *
 * ===================== 关于「固定窗口」这个选择 =====================
 * 这里用的是固定窗口计数（INCR + EXPIRE），实现最简单。
 * 它的已知缺陷是**临界问题**：窗口边界前后各打满一次，实际瞬时速率可达 2 倍上限。
 * 对秒杀场景这是可接受的 —— 只要下游有库存和 DB 双重兜底，多放一点流量进来不会造成超卖。
 * 如果业务对速率要求严格（比如按量计费的 API），应该换成滑动窗口或令牌桶。
 *
 * Created 2026/09/30.
 */
@Component
public class SeckillRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillRateLimiter.class);

    /** 用户维度：同一用户对同一商品，窗口 10 秒内最多 5 次 */
    public static final int USER_LIMIT = 5;
    public static final int USER_WINDOW_SECONDS = 10;

    /** 商品维度：同一商品全局每秒最多 2000 次（保护下游 MQ / DB） */
    public static final int PRODUCT_LIMIT = 2000;
    public static final int PRODUCT_WINDOW_SECONDS = 1;

    /**
     * 固定窗口计数脚本。
     *
     * 必须用 Lua 的原因：INCR 和 EXPIRE 如果分两次发，
     * 「INCR 之后进程挂掉」会留下一个永不过期的 key，
     * 那个维度的限流就会永久卡死。合成一个脚本才能保证"要么都做、要么都不做"。
     *
     * 返回值：1 = 放行，0 = 被限流
     */
    private static final String LUA_RATE_LIMIT = """
            local key    = KEYS[1]
            local limit  = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])

            local current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, window)
            end
            if current > limit then
                return 0
            end
            return 1
            """;

    private final DefaultRedisScript<Long> rateLimitScript =
            new DefaultRedisScript<>(LUA_RATE_LIMIT, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用户维度限流。
     *
     * 【输入】memberId + 商品维度标识
     * 【输出】true = 放行；false = 触发限流
     */
    public boolean tryAcquireByUser(Long memberId, Long promotionId, Long sessionId, Long productId) {
        return tryAcquire(SeckillStockManager.KEY_PREFIX + "rl:user:"
                        + promotionId + ":" + sessionId + ":" + productId + ":" + memberId,
                USER_LIMIT, USER_WINDOW_SECONDS);
    }

    /**
     * 商品维度限流（全局，跨用户）。
     */
    public boolean tryAcquireByProduct(Long promotionId, Long sessionId, Long productId) {
        return tryAcquire(SeckillStockManager.KEY_PREFIX + "rl:product:"
                + promotionId + ":" + sessionId + ":" + productId,
                PRODUCT_LIMIT, PRODUCT_WINDOW_SECONDS);
    }

    private boolean tryAcquire(String key, int limit, int windowSeconds) {
        Long allowed = stringRedisTemplate.execute(rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(limit), String.valueOf(windowSeconds));
        boolean pass = allowed != null && allowed == 1L;
        if (!pass) {
            LOGGER.debug("触发秒杀限流：key={} limit={}/{}s", key, limit, windowSeconds);
        }
        return pass;
    }
}
