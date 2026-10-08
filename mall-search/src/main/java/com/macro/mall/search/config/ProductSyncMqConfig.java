package com.macro.mall.search.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品索引同步的 MQ 配置（消费者侧）。
 *
 * 与 mall-admin 的 ProductSyncMqConfig 声明完全一致 —— 两边都声明是为了避免启动顺序问题：
 * 队列必须存在消息才投得进去，如果只在消费端声明，
 * 消费端没起来时生产端发的消息会被静默丢弃（投到不存在的路由键不报错）。
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
