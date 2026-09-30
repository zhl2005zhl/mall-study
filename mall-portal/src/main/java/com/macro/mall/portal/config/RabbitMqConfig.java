package com.macro.mall.portal.config;

import com.macro.mall.portal.domain.QueueEnum;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列相关配置
 * Created by macro on 2018/9/14.
 */
@Configuration
public class RabbitMqConfig {

    /**
     * 订单消息实际消费队列所绑定的交换机
     */
    @Bean
    DirectExchange orderDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单延迟队列队列所绑定的交换机
     */
    @Bean
    DirectExchange orderTtlDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单实际消费队列
     */
    @Bean
    public Queue orderQueue() {
        return new Queue(QueueEnum.QUEUE_ORDER_CANCEL.getName());
    }

    /**
     * 订单延迟队列（死信队列）
     */
    @Bean
    public Queue orderTtlQueue() {
        return QueueBuilder
                .durable(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getName())
                .withArgument("x-dead-letter-exchange", QueueEnum.QUEUE_ORDER_CANCEL.getExchange())//到期后转发的交换机
                .withArgument("x-dead-letter-routing-key", QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey())//到期后转发的路由键
                .build();
    }

    /**
     * 将订单队列绑定到交换机
     */
    @Bean
    Binding orderBinding(DirectExchange orderDirect,Queue orderQueue){
        return BindingBuilder
                .bind(orderQueue)
                .to(orderDirect)
                .with(QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey());
    }

    /**
     * 将订单延迟队列绑定到交换机
     */
    @Bean
    Binding orderTtlBinding(DirectExchange orderTtlDirect,Queue orderTtlQueue){
        return BindingBuilder
                .bind(orderTtlQueue)
                .to(orderTtlDirect)
                .with(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getRouteKey());
    }

    /* ==================== 秒杀下单队列（异步削峰） ==================== */

    /**
     * 秒杀下单交换机
     */
    @Bean
    DirectExchange seckillDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_SECKILL_ORDER.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 秒杀死信交换机
     */
    @Bean
    DirectExchange seckillDlqDirect() {
        return ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_SECKILL_ORDER_DLQ.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 秒杀下单队列。
     *
     * 关键配置有两个：
     *   ① x-dead-letter-exchange / x-dead-letter-routing-key ——
     *      消费重试耗尽后，消息会被投到死信交换机，而不是被丢弃或无限重投。
     *      秒杀场景绝对不能"无限重投"：那会把队列堵死，后面正常的下单消息全进不来。
     *   ② 没有给队列设 x-message-ttl —— 消息本身不该有 TTL，
     *      因为它的生命周期应该由"是否处理成功"决定，而不是时间。
     *      （对比上面的 orderTtlQueue：那个队列的 TTL 是它的核心语义。）
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder
                .durable(QueueEnum.QUEUE_SECKILL_ORDER.getName())
                .withArgument("x-dead-letter-exchange", QueueEnum.QUEUE_SECKILL_ORDER_DLQ.getExchange())
                .withArgument("x-dead-letter-routing-key", QueueEnum.QUEUE_SECKILL_ORDER_DLQ.getRouteKey())
                .build();
    }

    /**
     * 秒杀死信队列：存放重试耗尽的消息，由补偿任务定期捞取
     */
    @Bean
    public Queue seckillOrderDlq() {
        return QueueBuilder.durable(QueueEnum.QUEUE_SECKILL_ORDER_DLQ.getName()).build();
    }

    @Bean
    Binding seckillOrderBinding(DirectExchange seckillDirect, Queue seckillOrderQueue) {
        return BindingBuilder
                .bind(seckillOrderQueue)
                .to(seckillDirect)
                .with(QueueEnum.QUEUE_SECKILL_ORDER.getRouteKey());
    }

    @Bean
    Binding seckillOrderDlqBinding(DirectExchange seckillDlqDirect, Queue seckillOrderDlq) {
        return BindingBuilder
                .bind(seckillOrderDlq)
                .to(seckillDlqDirect)
                .with(QueueEnum.QUEUE_SECKILL_ORDER_DLQ.getRouteKey());
    }

}
