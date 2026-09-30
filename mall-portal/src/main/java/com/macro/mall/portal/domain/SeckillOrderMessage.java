package com.macro.mall.portal.domain;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 秒杀下单消息（MQ 消息体）
 *
 * 设计要点：
 *   1. **自带商品快照**（名称/图片/价格），消费者落库时不用再回查商品表 ——
 *      既省一次查询，也避免"消费者处理时秒杀已结束/商品已改价"导致落库数据与用户看到的不一致。
 *   2. **带 requestId 做幂等键**，消费端用它防重复消费（MQ 是 at-least-once 投递）。
 *   3. **带 seckillPrice 和 relationId**，落库后支付/取消/对账都需要它们定位秒杀库存行。
 *
 * Created 2026/09/30.
 */
@Data
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求唯一标识：消费端幂等键（同一 requestId 只允许建一单） */
    private String requestId;

    private Long memberId;
    private String memberUsername;

    private Long flashPromotionId;
    private Long flashPromotionSessionId;
    /** 秒杀关系表主键，后续回滚/对账用它定位库存行 */
    private Long relationId;

    private Long productId;
    /** 商品快照：落库时直接用，不再回查商品表 */
    private String productName;
    private String productPic;
    private String productSn;
    private Long productSkuId;
    private String productSkuCode;
    private Long productCategoryId;
    private String productBrand;

    /** 秒杀价（服务端从 DB 取，绝不信任前端） */
    private BigDecimal seckillPrice;
    /** 购买数量 */
    private Integer quantity;

    /* ---- 收货信息快照 ---- */
    private Long memberReceiveAddressId;
    private String receiverName;
    private String receiverPhone;
    private String receiverPostCode;
    private String receiverProvince;
    private String receiverCity;
    private String receiverRegion;
    private String receiverDetailAddress;

    private Integer payType;

    /** 下单请求时间（用于排查消息积压） */
    private Date createTime;
}
