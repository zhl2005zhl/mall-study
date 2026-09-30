package com.macro.mall.portal.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.domain.SeckillOrderMessage;
import com.macro.mall.portal.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单消息消费者。
 *
 * ======================= 消费端的三个必须做对的事 =======================
 *
 * 1. **幂等**。
 *    MQ 是 at-least-once 投递，同一条消息被消费两次是常态而不是异常。
 *    幂等的落点是 `sms_flash_promotion_order.request_id` 唯一索引 +
 *    `markSuccess` 的条件更新（只有 status=0 才能改成 1）。
 *    处理逻辑里第一件事就是查流水状态，已处理过就直接返回。
 *
 * 2. **失败必须回滚 Redis 库存**。
 *    预扣发生在 Redis，落库发生在消费者。中间任何一步失败（DB 报错、库存被抢完、
 *    超过限购），都必须把预扣的库存还回去,否则那件商品就永远卖不出去了 ——
 *    这是"漏卖"，虽然不如超卖严重，但会造成 Redis 与 DB 的库存永久性偏差。
 *
 * 3. **不能让消息无声消失**。
 *    处理失败时**抛异常**（而不是 catch 后自己 ack），让 Spring AMQP 的重试机制接管：
 *    按配置重试 N 次；仍然失败则进入死信队列，由补偿任务处理。
 *    如果在这里 catch 掉异常并正常返回，消息会被 ack 掉，那条预扣的库存就成了孤儿。
 *
 * Created 2026/09/30.
 */
@Component
@RabbitListener(queues = "mall.seckill.order")
public class SeckillOrderReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillOrderReceiver.class);

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeckillService seckillService;

    @RabbitHandler
    public void handle(String json) {
        SeckillOrderMessage message;
        try {
            message = objectMapper.readValue(json, SeckillOrderMessage.class);
        } catch (Exception e) {
            // 消息体本身解析不了 —— 重试多少次都没用，属于"毒消息"。
            // 直接抛出让它进死信队列（不在这里 ack 掉），由补偿任务人工介入，
            // 因为这条消息背后可能已经预扣了 Redis 库存，需要人工确认再回滚。
            LOGGER.error("秒杀消息解析失败，将进入死信队列。payload={}", json, e);
            throw new IllegalArgumentException("秒杀消息格式非法", e);
        }

        LOGGER.info("开始处理秒杀下单：requestId={} memberId={} productId={}",
                message.getRequestId(), message.getMemberId(), message.getProductId());

        // 由 Service 承载真正的业务逻辑；它内部保证幂等与库存补偿。
        // 这里不做 try-catch —— 失败就抛出，交给 Spring AMQP 的重试与死信机制。
        seckillService.handleSeckillOrder(message);
    }
}
