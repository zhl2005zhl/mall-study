package com.macro.mall.service;

import com.macro.mall.domain.PmsCommentQueryParam;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentReplay;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品评价管理（后台）
 *
 * Created 2026/10/08.
 */
public interface PmsCommentService {

    /**
     * 分页查询评价
     */
    List<PmsComment> list(PmsCommentQueryParam param);

    /**
     * 批量修改显示状态（审核）
     */
    int updateShowStatus(List<Long> ids, Integer showStatus);

    /**
     * 批量删除（同时删掉这些评价下的回复，避免留下孤儿回复）
     */
    int delete(List<Long> ids);

    /**
     * 回复一条评价
     */
    int reply(Long commentId, String content);

    /**
     * 查某条评价的回复列表
     */
    List<PmsCommentReplay> listReplay(Long commentId);
}
