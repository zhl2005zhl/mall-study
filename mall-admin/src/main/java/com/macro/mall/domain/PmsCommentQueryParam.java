package com.macro.mall.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 后台查询评价的筛选项
 *
 * Created 2026/10/08.
 */
@Data
public class PmsCommentQueryParam {

    @Schema(title = "商品名称（模糊匹配）")
    private String productName;

    @Schema(title = "会员昵称（模糊匹配）")
    private String memberNickName;

    @Schema(title = "评分：1-5")
    private Integer star;

    @Schema(title = "显示状态：0->隐藏；1->显示")
    private Integer showStatus;

    @Schema(title = "页码")
    private Integer pageNum = 1;

    @Schema(title = "每页条数")
    private Integer pageSize = 5;
}
