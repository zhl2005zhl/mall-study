package com.macro.mall.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品索引同步的 MQ 配置（生产者侧）。
 *
 * ==================== 队列拓扑 ====================
 *   交换机 mall.product.direct
 *     └─ 队列 mall.product.sync        ← 消费端在这里更新 ES 索引
 *          └─ 死信 → 交换机 mall.product.direct.dlq
 *                      └─ 队列 mall.product.sync.dlq   ← 重试耗尽后落这里
 *
 * ==================== 为什么生产端也要声明队列 ====================
 * 队列必须存在，消息才投得进去（投到不存在的路由键，消息会被直接丢弃且不报错）。
 * 如果只让消费端声明，就有个顺序问题：**消费端还没起来时，生产端改商品发的消息全丢了**。
 * 两边都声明（参数完全一致）时，RabbitMQ 会做幂等处理；
 * 万一参数不一致它会直接报错 —— 这反而是好事，能在启动时就发现配置写岔了。
 *
 * ==================== 为什么必须有死信队列 ====================
 * 索引同步是"允许延迟、但最终必须成功"。重试耗尽后如果消息被丢弃，
 * 那个商品就永远和索引不一致了，而且**没有任何痕迹**。
 * 落进死信队列后至少能被捞出来排查/补偿。
 *
 * 异步消息一定要配死信队列的原因：**"丢了"和"失败了"是两件事，
 * 前者无从查起，后者至少有现场。**
 *
 * Created 2026/10/08.
 */
@Configuration
public class ProductSyncMqConfig {

    public static final String EXCHANGE = "mall.product.direct";
    public static final String QUEUE = "mall.product.sync";
    public static final String ROUTE_KEY = "mall.product.sync";

    public static final String DLQ_EXCHANGE = "mall.product.direct.dlq";
    public static final String DLQ_QUEUE = "mall.product.sync.dlq";
    public static final String DLQ_ROUTE_KEY = "mall.product.sync.dlq";

    @Bean
    DirectExchange productSyncDirect() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange productSyncDlqDirect() {
        return ExchangeBuilder.directExchange(DLQ_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue productSyncQueue() {
        return QueueBuilder
                .durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTE_KEY)
                .build();
    }

    @Bean
    public Queue productSyncDlq() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    Binding productSyncBinding(DirectExchange productSyncDirect, Queue productSyncQueue) {
        return BindingBuilder.bind(productSyncQueue).to(productSyncDirect).with(ROUTE_KEY);
    }

    @Bean
    Binding productSyncDlqBinding(DirectExchange productSyncDlqDirect, Queue productSyncDlq) {
        return BindingBuilder.bind(productSyncDlq).to(productSyncDlqDirect).with(DLQ_ROUTE_KEY);
    }
}
