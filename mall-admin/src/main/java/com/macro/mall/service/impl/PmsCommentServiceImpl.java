package com.macro.mall.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.dao.PmsCommentDao;
import com.macro.mall.domain.PmsCommentQueryParam;
import com.macro.mall.mapper.PmsCommentMapper;
import com.macro.mall.mapper.PmsCommentReplayMapper;
import com.macro.mall.model.*;
import com.macro.mall.service.PmsCommentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;

/**
 * 商品评价管理实现（后台）
 *
 * Created 2026/10/08.
 */
@Service
public class PmsCommentServiceImpl implements PmsCommentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PmsCommentServiceImpl.class);

    /**
     * 单次批量操作的上限。
     *
     * ★ 这条上限是**台账 #27 的教训直接落过来的**：
     *   #27 的问题是「把所有超时订单 id 拼成一个 IN 列表」，积压 33 万条时
     *   拼出 10MB SQL，直接超过 max_allowed_packet 把整批干挂。
     *
     *   虽然这里的 ids 来自管理端请求（不会像定时任务那样一次捞出几十万条），
     *   但"依赖调用方自觉"不是设计 —— 在入口加一个上限，成本几乎为零，
     *   收益是"这个接口永远不会因为参数过大而炸"。
     *   同一个坑不要踩第二次。
     */
    private static final int MAX_BATCH_SIZE = 500;

    /** 显示状态：1 = 显示 */
    private static final int SHOW_STATUS_VISIBLE = 1;
    private static final int SHOW_STATUS_HIDDEN = 0;

    @Autowired
    private PmsCommentDao commentDao;
    @Autowired
    private PmsCommentMapper commentMapper;
    @Autowired
    private PmsCommentReplayMapper commentReplayMapper;

    @Override
    public List<PmsComment> list(PmsCommentQueryParam param) {
        PageHelper.startPage(param.getPageNum(), param.getPageSize());
        // selectByExample 不带 BLOBs（content 是 text 类型），列表页用不到正文，
        // 少传一点数据；要正文的详情接口再单独查。
        return commentDao.selectList(param);
    }

    @Override
    public int updateShowStatus(List<Long> ids, Integer showStatus) {
        validateBatch(ids, "评价ID列表");
        if (showStatus == null
                || (showStatus != SHOW_STATUS_VISIBLE && showStatus != SHOW_STATUS_HIDDEN)) {
            Asserts.fail("显示状态只能是 0（隐藏）或 1（显示）");
        }
        int count = commentDao.updateShowStatus(ids, showStatus);
        LOGGER.info("批量审核评价：{} 条置为 showStatus={}", count, showStatus);
        return count;
    }

    @Override
    // 删评价要同时删回复，两步必须整体成败 —— 否则会留下"回复还在、评价没了"的孤儿数据。
    // 而孤儿回复不只是一个数据整洁问题：前台按 comment_id 查回复时查不到，
    // 但那条回复确实还占着存储、也还可能在别的地方被引用。
    @Transactional
    public int delete(List<Long> ids) {
        validateBatch(ids, "评价ID列表");
        // 先删回复（子），再删评价（主）—— 顺序不能反：
        // 反过来的话，删完评价就找不到"哪些回复属于它"了。
        PmsCommentReplayExample replayExample = new PmsCommentReplayExample();
        replayExample.createCriteria().andCommentIdIn(ids);
        int replayDeleted = commentReplayMapper.deleteByExample(replayExample);

        int count = commentDao.deleteByIds(ids);
        LOGGER.info("批量删除评价：评价 {} 条、连带回复 {} 条", count, replayDeleted);
        return count;
    }

    @Override
    // 回复数 +1 与插入回复必须在一起：否则会出现"回复列表有 2 条、但计数显示 1"
    @Transactional
    public int reply(Long commentId, String content) {
        if (commentId == null) {
            Asserts.fail("评价ID不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            Asserts.fail("回复内容不能为空");
        }
        PmsComment comment = commentMapper.selectByPrimaryKey(commentId);
        if (comment == null) {
            Asserts.fail("评价不存在");
        }

        PmsCommentReplay replay = new PmsCommentReplay();
        replay.setCommentId(commentId);
        // 后台回复统一用一个固定昵称，方便前台区分"官方回复"和"用户回复"
        replay.setMemberNickName("官方客服");
        replay.setMemberIcon(null);
        replay.setContent(content);
        replay.setCreateTime(new Date());
        // type：0 = 用户回复评论，1 = 商家回复评论。
        // 这个字段决定了前台展示时"谁在跟谁说话"，不填的话两条回复看起来一样长。
        replay.setType(1);
        int count = commentReplayMapper.insertSelective(replay);

        // 原子自增回复数（相对更新，不是查出来加一再写回）
        commentDao.incrReplayCount(commentId);
        LOGGER.info("回复评价：commentId={}，回复ID={}", commentId, replay.getId());
        return count;
    }

    @Override
    public List<PmsCommentReplay> listReplay(Long commentId) {
        PmsCommentReplayExample example = new PmsCommentReplayExample();
        example.createCriteria().andCommentIdEqualTo(commentId);
        example.setOrderByClause("create_time asc");
        return commentReplayMapper.selectByExample(example);
    }

    /**
     * 批量操作的入参校验：非空 + 不超过上限。
     *
     * 抽成一个方法而不是每个接口各写一遍，是因为这两条校验是**所有批量接口的共同要求**，
     * 分散写必然会漏（#27 就是因为"某一条路径漏了上限"才出问题）。
     */
    private void validateBatch(List<Long> ids, String fieldName) {
        if (CollectionUtils.isEmpty(ids)) {
            Asserts.fail(fieldName + "不能为空");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            Asserts.fail("单次最多操作 " + MAX_BATCH_SIZE + " 条，当前 " + ids.size() + " 条");
        }
    }
}
