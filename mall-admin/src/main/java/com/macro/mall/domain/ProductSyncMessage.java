package com.macro.mall.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 商品索引同步消息。
 *
 * ==================== 为什么消息里「只有 id，没有操作类型」====================
 *
 * 直觉上会设计成这样：{operation: "DELETE", productId: 3}，
 * 让消费者照着执行。但这是个陷阱：
 *
 *   · 增量语义会**漂移**。假设"下架"发了 DELETE、"上架"发了 UPSERT，
 *     中间有一条消息丢了（网络抖动、消费者崩了没重启），
 *     消费端的状态就永远和数据库不一致了 —— 而且**没有任何机制能发现**，
 *     因为每条消息单独看都是"对的"。
 *
 *   · 同一个商品短时间内连续变更时，消息顺序不保证。先发 UPSERT 再发 DELETE，
 *     MQ 乱序投递就会让最终结果反过来。
 *
 * 所以这里改成**状态收敛语义**：消息只表达"这几个商品的状态可能变了，
 * 请重新对齐一下"。消费端拿到 id 后回查数据库，问自己一个问题：
 * **"这个商品现在应该出现在索引里吗？"** —— 应该就 upsert，不应该就 delete。
 *
 * 这样带来的好处：
 *   ① **自愈**：任何一条消息到达都能纠正此前的所有偏差，不依赖消息数量与顺序
 *   ② **去重天然成立**：同一条消息消费 10 次结果完全一样（幂等）
 *   ③ **逻辑只有一个出口**：判断"该不该上榜"这件事只有一处实现，
 *      不会出现"下架路径忘了删索引"这种漏一个分支的 bug
 *
 * 代价是消费端多一次数据库查询 —— 但这个查询走主键，成本极低，
 * 换来的是整个链路不依赖"消息不丢不重不乱序"这个不可能成立的假设。
 *
 * Created 2026/10/08.
 */
@Data
public class ProductSyncMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 需要重新对齐索引状态的商品 id 列表。
     * 用 List 而不是单个 id：批量操作（上下架、删除）一次动几十上百个商品时，
     * 一条消息比一百条消息便宜得多，也避免了"消息发到一半失败"的半成品状态。
     */
    @Schema(title = "需要重新对齐索引状态的商品ID列表")
    private List<Long> productIds;

    @Schema(title = "触发来源，仅用于排查（如 updatePublishStatus）")
    private String source;

    @Schema(title = "产生时间，用于排查消息积压")
    private Date createTime;
}
