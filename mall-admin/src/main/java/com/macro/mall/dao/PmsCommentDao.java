package com.macro.mall.dao;

import com.macro.mall.domain.PmsCommentQueryParam;
import com.macro.mall.model.PmsComment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品评价的自定义查询（管理侧）
 *
 * Created 2026/10/08.
 */
public interface PmsCommentDao {

    /**
     * 按条件分页查询评价。
     *
     * 用自定义 SQL 而不是 Example 的原因：这里要按**商品名模糊搜索**，
     * 而 pms_comment 表里虽然冗余了 product_name，但"搜索"要支持左模糊，
     * 用 Example 拼 `like '%xx%'` 也可以，只是当查询条件变多（星级 + 状态 + 关键词 + 时间范围）时，
     * 手写 SQL 的可读性明显更好 —— 一眼能看出"哪些条件是可选的"。
     */
    List<PmsComment> selectList(@Param("param") PmsCommentQueryParam param);

    /**
     * 批量修改显示状态（审核）
     *
     * 用 foreach 一次更新多条，而不是循环单条 update：
     * 后者是 N 次往返，而且没有原子性 —— 中途失败会留下"一半已审、一半未审"的中间状态。
     */
    int updateShowStatus(@Param("ids") List<Long> ids, @Param("showStatus") Integer showStatus);

    /**
     * 批量删除
     */
    int deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 插入回复，并同步把 pms_comment.replay_count 加一。
     *
     * 注意 replay_count 不在插入回复后单独更新，而是**由调用方在同一个事务里做**，
     * 见 PmsCommentServiceImpl —— 这样才能保证"回复数"和"回复明细"不会对不上。
     */
    int incrReplayCount(@Param("commentId") Long commentId);
}
