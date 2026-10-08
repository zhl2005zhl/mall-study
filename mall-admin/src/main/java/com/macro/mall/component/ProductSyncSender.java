package com.macro.mall.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.config.ProductSyncMqConfig;
import com.macro.mall.domain.ProductSyncMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 商品索引同步消息发送者。
 *
 * ==================== 关键设计：发送失败绝不能影响主流程 ====================
 * 这个方法被商品保存/上下架/删除等主流程调用。它内部**吞掉所有异常只打日志**，
 * 而不是抛出去。
 *
 * 为什么？因为**索引同步是"锦上添花"，商品数据本身才是不可丢的**：
 *   · 如果发消息失败就抛异常 → 整个商品保存事务回滚 →
 *     用户改了 10 个字段，因为 MQ 抖了一下，一个都没保存成功。
 *     这是典型的"为了次要目标牺牲主要目标"。
 *   · 就算这条消息真的永久丢了，还有**定时对账**兜底
 *     （见 mall-search 的 EsSyncReconcileTask）—— 这正是台账里
 *     "推送负责实时性、拉取负责正确性"的意思。
 *
 * 但这个决定有个前提：**必须有对账兜底**。
 * 如果没有对账，吞异常就等于"静默丢数据"，那是灾难。
 * 所以这两件事必须一起做，不能只做一半。
 *
 * Created 2026/10/08.
 */
@Component
public class ProductSyncSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSyncSender.class);

    @Autowired
    private AmqpTemplate amqpTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 通知搜索服务：这些商品的索引状态需要重新对齐。
     *
     * @param productIds 发生变更的商品 id
     * @param source     触发来源（如 create / updatePublishStatus），仅用于排查
     */
    public void notifyProductChanged(List<Long> productIds, String source) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        try {
            ProductSyncMessage message = new ProductSyncMessage();
            message.setProductIds(productIds);
            message.setSource(source);
            message.setCreateTime(new Date());

            amqpTemplate.convertAndSend(
                    ProductSyncMqConfig.EXCHANGE,
                    ProductSyncMqConfig.ROUTE_KEY,
                    objectMapper.writeValueAsString(message));

            LOGGER.info("已投递商品索引同步消息：source={} count={} ids={}",
                    source, productIds.size(), productIds);
        } catch (Exception e) {
            // 故意吞掉：索引同步失败不该回滚商品保存。
            // 但必须打 ERROR —— 它不致命，却说明"实时通道"这条腿断了，只能靠对账补救。
            LOGGER.error("★ 商品索引同步消息投递失败（不影响商品保存，将由定时对账兜底）："
                    + "source={} ids={}", source, productIds, e);
        }
    }

    /**
     * 单个商品的便捷方法。
     */
    public void notifyProductChanged(Long productId, String source) {
        if (productId != null) {
            notifyProductChanged(List.of(productId), source);
        }
    }
}
