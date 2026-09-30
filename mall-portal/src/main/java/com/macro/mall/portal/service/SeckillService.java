package com.macro.mall.portal.service;

import com.macro.mall.portal.domain.FlashPromotionStockInfo;
import com.macro.mall.portal.domain.HomeFlashPromotion;
import com.macro.mall.portal.domain.SeckillOrderMessage;
import com.macro.mall.portal.domain.SeckillRequest;
import com.macro.mall.portal.domain.SeckillResult;

import java.util.List;

/**
 * 秒杀服务
 *
 * 完整链路一共 7 个环节，对应下面 7 个方法（按调用顺序排列）：
 *
 *   ① 活动配置      loadActivePromotion / listSeckillProducts   —— 读活动与场次
 *   ② 库存预热      warmUp                                      —— DB 剩余库存 → Redis
 *   ③ 限流+防超卖   submit（内部调 RateLimiter + StockManager）  —— Lua 原子预扣
 *   ④ 异步削峰      submit 内部发 MQ                              —— 接口立即返回排队中
 *   ⑤ 下单落库      handleSeckillOrder                          —— 消费者建单
 *   ⑥ 超时回滚      rollbackSeckillStock                        —— 取消订单时回滚
 *   ⑦ 一致性校验    reconcile                                   —— Redis vs DB 对账
 *
 * Created 2026/09/30.
 */
public interface SeckillService {

    /**
     * ② 库存预热：把 DB 的剩余秒杀库存灌进 Redis。
     *
     * @param force true = 强制以 DB 为准重建（会打告警日志）；false = 已预热则跳过
     * @return 本次预热的商品数
     */
    int warmUp(Long promotionId, Long sessionId, boolean force);

    /**
     * ① 当前生效的秒杀场次信息（供前台展示，与首页的口径保持一致）
     */
    HomeFlashPromotion getCurrentSeckill();

    /**
     * ① 查某活动×场次下的秒杀商品列表（用于页面展示，剩余库存取 Redis，未预热时回落到 DB）
     */
    List<FlashPromotionStockInfo> listSeckillProducts(Long promotionId, Long sessionId);

    /**
     * ③④ 提交秒杀下单：限流 → 预扣库存 → 落流水 → 发 MQ → 返回「排队中」
     */
    SeckillResult submit(SeckillRequest request);

    /**
     * ⑤ 消费者：真正建单（保证幂等 + 失败时补偿库存）
     */
    void handleSeckillOrder(SeckillOrderMessage message);

    /**
     * 异步结果查询：前端拿 requestId 轮询
     */
    SeckillResult getResult(String requestId);

    /**
     * ⑥ 回滚秒杀库存（取消订单 / 补偿时调用）—— Redis 与 DB 都要回滚
     */
    void rollbackSeckillStock(Long relationId, Long productId, Long flashPromotionId,
                              Long flashPromotionSessionId, Long memberId, int quantity);

    /**
     * ⑦ 最终一致性校验：比对 Redis 与 DB 的剩余库存，发现分歧则修正 Redis 并告警
     *
     * @return 发现并修复的分歧数量
     */
    int reconcile(Long promotionId, Long sessionId);
}
