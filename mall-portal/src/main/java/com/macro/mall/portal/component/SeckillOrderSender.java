package com.macro.mall.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.domain.QueueEnum;
import com.macro.mall.portal.domain.SeckillOrderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单消息发送者。
 *
 * ==================== 为什么秒杀必须走异步 ====================
 * 秒杀接口在预扣 Redis 库存后要立刻返回，不能等数据库建单完成 ——
 * 建单涉及多张表写入（订单、订单项、扣真实库存、写流水），是几十毫秒级操作，
 * 如果同步做，「抢购成功后排队等结果」的体验会极差，而且数据库连接会被瞬时打满。
 *
 * 所以链路是：预扣 Redis 库存（毫秒级）→ 发 MQ → 立即返回「排队中」→
 *            消费者慢慢建单 → 用户拿 requestId 查结果。
 *
 * 这就是所谓的"异步削峰"：把瞬时高峰的写入压力摊平到一段时间内。
 *
 * ============ 关于消息体的序列化方式（一个刻意的选择）============
 * 这里用 **JSON 字符串** 发送，而不是注册一个全局的 Jackson2JsonMessageConverter。
 * 原因：项目里已有的 CancelOrderSender 发的是原始 Long（订单ID），
 * 如果注册全局 JSON converter，那条链路收到的会是 JSON 数字/对象的差异，
 * 有把已有功能改坏的风险。**修新功能不该顺手改动老链路的行为。**
 * 用字符串传 JSON 是改动最小、最不会互相影响的做法。
 *
 * Created 2026/09/30.
 */
@Component
public class SeckillOrderSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillOrderSender.class);

    @Autowired
    private AmqpTemplate amqpTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 发送秒杀下单消息。
     *
     * 【异常策略】这里**不吞异常**：如果发消息失败，调用方必须知道，
     * 因为 Redis 库存已经预扣了 —— 消息发不出去就必须立刻回滚，否则这个库存永远悬空。
     * 所以这里让它抛出去，由调用方在 catch 里做 release。
     */
    public void sendSeckillOrder(SeckillOrderMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            amqpTemplate.convertAndSend(
                    QueueEnum.QUEUE_SECKILL_ORDER.getExchange(),
                    QueueEnum.QUEUE_SECKILL_ORDER.getRouteKey(),
                    json);
            LOGGER.info("秒杀下单消息已投递：requestId={} memberId={} productId={}",
                    message.getRequestId(), message.getMemberId(), message.getProductId());
        } catch (Exception e) {
            // 序列化失败或 MQ 不可用 —— 直接抛出，让调用方回滚 Redis 预扣
            throw new IllegalStateException("秒杀下单消息投递失败：" + e.getMessage(), e);
        }
    }
}
