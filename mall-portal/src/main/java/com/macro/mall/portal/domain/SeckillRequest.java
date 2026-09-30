package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 秒杀下单入参
 *
 * 注意这里「不接收」商品价格、库存、活动状态等任何可被篡改的字段 ——
 * 前端只能表达"我要买哪个活动的哪个商品的哪个数量"，
 * 价格与库存一律由服务端从数据库/Redis 取。
 * 这是秒杀接口最容易出的安全问题：让前端传价格，就等于把定价权交给了客户端。
 *
 * Created 2026/09/30.
 */
@Data
public class SeckillRequest {

    @Schema(title = "秒杀活动ID")
    @NotNull(message = "活动ID不能为空")
    private Long flashPromotionId;

    @Schema(title = "秒杀场次ID")
    @NotNull(message = "场次ID不能为空")
    private Long flashPromotionSessionId;

    @Schema(title = "商品ID")
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @Schema(title = "购买数量（秒杀通常限 1，但留出扩展空间）")
    private Integer quantity = 1;

    @Schema(title = "收货地址ID")
    @NotNull(message = "收货地址不能为空")
    private Long memberReceiveAddressId;

    @Schema(title = "支付方式：1->支付宝；2->微信")
    private Integer payType;
}
