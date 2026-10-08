package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentReplay;
import com.macro.mall.portal.domain.PmsCommentParam;
import com.macro.mall.portal.domain.PmsCommentSummary;
import com.macro.mall.portal.service.PmsCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品评价接口（会员侧）
 *
 * 【接口清单】
 *   POST /member/comment/create              发表评价（需登录）
 *   GET  /member/comment/list                某商品的评价列表（公开）
 *   GET  /member/comment/summary             某商品的评价统计（公开）
 *   GET  /member/comment/replay/{commentId}  某条评价的回复（公开）
 *   PUT  /member/comment/readCount/{id}      阅读数 +1（公开）
 *   PUT  /member/comment/collect/{id}        点赞 +1（需登录）
 *
 * 【为什么查询类接口做成公开的】
 * 商品评价是**给所有浏览者看的**（用来决策买不买），不是私人数据。
 * 要求登录才能看评价，等于把最重要的转化依据藏起来。
 * 而"发表评价"必须登录 —— 因为要绑定"谁买的"。
 *
 * Created 2026/10/08.
 */
@Controller
@Tag(name = "PmsCommentController", description = "商品评价（会员侧）")
@RequestMapping("/member/comment")
public class PmsCommentController {

    @Autowired
    private PmsCommentService commentService;

    @Operation(summary = "发表商品评价（只能评价自己已确认收货的商品，且每商品限一次）")
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> create(@Validated @RequestBody PmsCommentParam param) {
        return CommonResult.success(commentService.create(param));
    }

    @Operation(summary = "分页查询某商品的评价（只返回审核通过的评价）")
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<PmsComment>> list(@RequestParam Long productId,
                                                     @RequestParam(required = false, defaultValue = "1") Integer pageNum,
                                                     @RequestParam(required = false, defaultValue = "5") Integer pageSize) {
        return CommonResult.success(commentService.listByProduct(productId, pageNum, pageSize));
    }

    @Operation(summary = "查询某商品的评价统计（总数 / 好评率 / 星级分布）")
    @RequestMapping(value = "/summary", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<PmsCommentSummary> summary(@RequestParam Long productId) {
        return CommonResult.success(commentService.summary(productId));
    }

    @Operation(summary = "查询某条评价的回复列表")
    @RequestMapping(value = "/replay/{commentId}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<List<PmsCommentReplay>> replay(@PathVariable Long commentId) {
        return CommonResult.success(commentService.listReplay(commentId));
    }

    @Operation(summary = "评价阅读数 +1")
    @RequestMapping(value = "/readCount/{id}", method = RequestMethod.PUT)
    @ResponseBody
    public CommonResult<Void> readCount(@PathVariable Long id) {
        commentService.incrReadCount(id);
        return CommonResult.success(null);
    }

    @Operation(summary = "评价点赞 +1")
    @RequestMapping(value = "/collect/{id}", method = RequestMethod.PUT)
    @ResponseBody
    public CommonResult<Void> collect(@PathVariable Long id) {
        commentService.incrCollectCount(id);
        return CommonResult.success(null);
    }
}
