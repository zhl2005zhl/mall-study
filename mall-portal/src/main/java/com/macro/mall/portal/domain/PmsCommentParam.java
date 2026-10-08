package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发表商品评价的入参。
 *
 * 注意这里**不接收** memberNickName / memberIcon / productName / showStatus / 各种计数 ——
 * 这些都必须由服务端填：
 *   · 昵称头像来自当前登录态（不然可以冒别人的名发评价）
 *   · 商品名来自数据库（不然评价里可以写任意商品名）
 *   · showStatus 由审核流程决定，不能让发布者自己决定"已被审核通过"
 *   · 阅读数/点赞数/回复数由系统维护
 *
 * 这和秒杀接口「不接收价格」是同一条原则：**客户端只能表达意图，不能表达事实。**
 *
 * Created 2026/10/08.
 */
@Data
public class PmsCommentParam {

    @Schema(title = "商品ID")
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @Schema(title = "评分：1-5 星")
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer star;

    @Schema(title = "评价内容")
    private String content;

    @Schema(title = "晒图，多张用逗号分隔")
    private String pics;

    @Schema(title = "所购规格，如「颜色:黑色;内存:64G」")
    private String productAttribute;
}
