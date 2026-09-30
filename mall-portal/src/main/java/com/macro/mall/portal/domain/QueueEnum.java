package com.macro.mall.portal.domain;

import lombok.Getter;

/**
 * 消息队列枚举类
 * Created by macro on 2018/9/14.
 */
@Getter
public enum QueueEnum {
    /**
     * 消息通知队列
     */
    QUEUE_ORDER_CANCEL("mall.order.direct", "mall.order.cancel", "mall.order.cancel"),
    /**
     * 消息通知ttl队列
     */
    QUEUE_TTL_ORDER_CANCEL("mall.order.direct.ttl", "mall.order.cancel.ttl", "mall.order.cancel.ttl"),
    /**
     * 秒杀下单队列（异步削峰：接口只发消息，真正的建单在消费者里做）
     */
    QUEUE_SECKILL_ORDER("mall.seckill.direct", "mall.seckill.order", "mall.seckill.order"),
    /**
     * 秒杀下单死信队列
     *
     * 什么时候会进这里：消费者重试耗尽（消息格式错误、DB 长时间不可用等）。
     * 进死信队列后不会被丢弃，而是由定时补偿任务捞出来处理 ——
     * 要么重试成功，要么明确标记失败并回滚 Redis 库存。
     * **绝不能让消息"悄悄消失"**，因为它在 Redis 里占着库存，丢了就永远对不上账。
     */
    QUEUE_SECKILL_ORDER_DLQ("mall.seckill.direct.dlq", "mall.seckill.order.dlq", "mall.seckill.order.dlq");

    /**
     * 交换名称
     */
    private final String exchange;
    /**
     * 队列名称
     */
    private final String name;
    /**
     * 路由键
     */
    private final String routeKey;

    QueueEnum(String exchange, String name, String routeKey) {
        this.exchange = exchange;
        this.name = name;
        this.routeKey = routeKey;
    }
}
