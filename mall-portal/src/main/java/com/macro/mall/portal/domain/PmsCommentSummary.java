package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 某个商品的评价统计。
 *
 * 【为什么一次性把 6 个数字都查出来，而不是分 3 次查】
 * 「总数」「好评率」「星级分布」如果用三条 SQL 分别查，会有两个问题：
 *   ① 三次查询之间数据可能变化（有人正好在发评价），导致"总数 100、但五个星级加起来 101"
 *   ② 三次数据库往返，而它们扫的是同一批行 —— 纯浪费
 * 用一条 `SUM(CASE WHEN ...)` 把它压成一次扫描，两个问题都消失。
 * 这是「聚合统计」最典型的优化手法：**能一次扫完的，不要扫三次。**
 *
 * Created 2026/10/08.
 */
@Data
public class PmsCommentSummary {

    @Schema(title = "评价总数（仅统计已显示的）")
    private Integer totalCount = 0;

    @Schema(title = "好评数（4-5 星）")
    private Integer goodCount = 0;

    @Schema(title = "好评率（0-100，保留 1 位小数）")
    private BigDecimal goodRate = BigDecimal.ZERO;

    @Schema(title = "5 星数")
    private Integer star5 = 0;
    @Schema(title = "4 星数")
    private Integer star4 = 0;
    @Schema(title = "3 星数")
    private Integer star3 = 0;
    @Schema(title = "2 星数")
    private Integer star2 = 0;
    @Schema(title = "1 星数")
    private Integer star1 = 0;
}
