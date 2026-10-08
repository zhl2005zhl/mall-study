package com.macro.mall.portal.service;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentReplay;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.domain.PmsCommentSummary;

import java.util.List;

/**
 * 商品评价服务（会员侧）
 *
 * Created 2026/10/08.
 */
public interface PmsCommentService {

    /**
     * 发表评价。
     *
     * 前置业务规则（缺一不可）：
     *   ① 该会员有「已完成」的订单包含这个商品 —— 没买过不能评
     *   ② 该会员对该商品还没评价过 —— 每人每商品只能一次
     */
    int create(PmsCommentParam param);

    /**
     * 分页查看某商品的评价（只返回审核通过、即 show_status=1 的）
     */
    CommonPage<PmsComment> listByProduct(Long productId, Integer pageNum, Integer pageSize);

    /**
     * 查某商品的评价统计（总数 / 好评率 / 星级分布）
     */
    PmsCommentSummary summary(Long productId);

    /**
     * 查某条评价的回复列表
     */
    List<PmsCommentReplay> listReplay(Long commentId);

    /**
     * 阅读数 +1
     */
    void incrReadCount(Long id);

    /**
     * 点赞 +1
     */
    void incrCollectCount(Long id);
}
