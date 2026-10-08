package com.macro.mall.portal.dao;

import com.macro.mall.model.PmsComment;
import com.macro.mall.portal.domain.PmsCommentSummary;
import org.apache.ibatis.annotations.Param;

/**
 * 商品评价的自定义查询（会员侧）
 *
 * 为什么需要手写 SQL 而不是全用 MBG 的 Example：
 *   1. 「评价统计」需要 `SUM(CASE WHEN ...)` 把 5 个星级的计数压成一次扫描，Example 表达不了
 *   2. 「校验是否买过」要跨 oms_order / oms_order_item 两张表，属于跨模块查询
 *   3. 计数自增要用 `字段 = 字段 + 1` 的相对更新（并发安全），不能查出来加一再写回
 *
 * Created 2026/10/08.
 */
public interface PmsCommentDao {

    /**
     * 插入一条评价。
     *
     * 为什么不用 MBG 生成的 `PmsCommentMapper.insertSelective`：
     * 那个方法是按生成时的表结构拼的字段列表，**不包含后补的 member_id**，
     * 用它插入会导致 member_id 永远为 NULL —— 去重和"我的评价"全都失效。
     * 所以这里手写 INSERT，把 member_id 显式带上。
     */
    int insertComment(PmsComment comment);

    /**
     * 校验：该会员是否有「已完成」的订单包含这个商品。
     *
     * 这是发评价的**前置业务规则**：只能评价自己真正买过并且已完成的商品。
     * 没有这条校验，任何人可以对任何商品刷评价 —— 评价体系就失去意义了。
     *
     * 为什么用订单状态 3（已完成）而不是"已发货"或"已支付"：
     * 只有收到货的人才有资格评价商品本身（描述是否相符、质量如何）。
     * 这是电商评价体系的通行规则。
     */
    int countMemberCompletedPurchase(@Param("memberId") Long memberId,
                                     @Param("productId") Long productId);

    /**
     * 统计该会员对该商品的已发表评价数 —— 用于「同一商品只能评价一次」的去重。
     *
     * 【已知简化】这里按「会员 + 商品」去重，语义是"每人每商品只能评价一次"。
     * 更精确的做法是按「会员 + 订单」去重（同一商品买两次可以评两次），
     * 但那需要 pms_comment 增加 order_id 字段并建 (order_id, product_id) 唯一索引。
     * 当前用前者是因为它不需要改表结构，且"每商品一次"本身也是很多电商的实际规则。
     * 要升级时按上面说的加字段即可，改动集中在这一个方法。
     */
    int countMemberComment(@Param("memberId") Long memberId,
                           @Param("productId") Long productId);

    /**
     * 一次查完评价统计：总数 + 好评数 + 五个星级的分布。
     *
     * 用一条 SQL 而不是三条的理由见 PmsCommentSummary 的注释：
     * 避免"三次查询之间数据变化导致总数与分布对不上"，同时省两次数据库往返。
     */
    PmsCommentSummary selectSummary(@Param("productId") Long productId);

    /**
     * 阅读数 +1（原子自增）
     *
     * 必须写成 `read_count = read_count + 1` 而不是"查出来加一再 update"：
     * 后者是典型的 read-modify-write，并发下会丢失更新
     * （两个请求都读到 100，都写回 101，实际应该 102）。
     * 这和台账 #1「锁库存丢失更新」是同一个问题的不同字段。
     */
    int incrReadCount(@Param("id") Long id);

    /**
     * 点赞数 +1（同上，原子自增）
     *
     * 【已知简化】没有做"同一用户只能点一次赞"——
     * 那需要一张 (comment_id, member_id) 的关系表来记录谁点过。
     * 当前表结构里 collect_couont 只是个计数，没有明细表，
     * 所以只能做到"计数自增"。要点出这个边界，而不是假装它防了重复点赞。
     */
    int incrCollectCount(@Param("id") Long id);
}
