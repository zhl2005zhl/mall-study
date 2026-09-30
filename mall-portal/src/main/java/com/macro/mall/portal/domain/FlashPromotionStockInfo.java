package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 秒杀库存信息（一行 = 一个活动×场次×商品）
 *
 * 这个对象是「Redis 预热」和「DB 对账」的共同数据源：
 *   · 预热时用它把 DB 的剩余库存灌进 Redis
 *   · 对账时用它拿到 DB 侧的权威剩余量，跟 Redis 里的值比对
 *
 * Created 2026/09/30.
 */
@Getter
@Setter
public class FlashPromotionStockInfo {

    @Schema(title = "秒杀关系表主键（后续扣减/回滚都用它定位）")
    private Long relationId;
    @Schema(title = "活动ID")
    private Long flashPromotionId;
    @Schema(title = "场次ID")
    private Long flashPromotionSessionId;
    @Schema(title = "商品ID")
    private Long productId;
    @Schema(title = "商品名称（用于错误提示）")
    private String productName;
    @Schema(title = "秒杀价")
    private java.math.BigDecimal flashPromotionPrice;
    @Schema(title = "秒杀总库存（配置值，不变）")
    private Integer flashPromotionCount;
    @Schema(title = "秒杀已售数量（DB 侧的权威计数）")
    private Integer flashPromotionSoldCount;
    @Schema(title = "每人限购数量")
    private Integer flashPromotionLimit;
    @Schema(title = "排序")
    private Integer sort;

    /**
     * DB 侧的剩余可售量 = 总库存 - 已售。
     *
     * 这是「最终权威」—— 因为已售数量是落库的，不依赖 Redis 是否可靠。
     * Redis 里的库存只是它的高速副本，两边不一致时以这个为准。
     */
    public int getRemainCount() {
        int total = flashPromotionCount == null ? 0 : flashPromotionCount;
        int sold = flashPromotionSoldCount == null ? 0 : flashPromotionSoldCount;
        return total - sold;
    }
}
