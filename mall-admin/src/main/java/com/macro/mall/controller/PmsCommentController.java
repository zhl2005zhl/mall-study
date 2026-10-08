package com.macro.mall.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.domain.PmsCommentQueryParam;
import com.macro.mall.model.PmsComment;
import com.macro.mall.model.PmsCommentReplay;
import com.macro.mall.service.PmsCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品评价管理（后台）
 *
 * 【接口清单】
 *   GET  /comment/list                 分页查询（可按商品名/昵称/星级/显示状态筛选）
 *   POST /comment/update/showStatus    批量审核（改显示状态）
 *   POST /comment/delete               批量删除（连带删除其下回复）
 *   POST /comment/reply                回复评价
 *   GET  /comment/replay/{commentId}   查看某条评价的回复
 *
 * 【后台管理侧的关键点：审核】
 * 前台只展示 show_status=1 的评价。这意味着后台有一个**内容把关**的职责：
 * 违规、广告、恶意差评都靠这道关卡拦下来。
 * 所以「评价默认是否可见」是个产品决策：
 *   · 默认可见（先发后审）：体验好，但有风险窗口
 *   · 默认隐藏（先审后发）：安全，但用户发完看不到自己的评价，体验差
 * 当前实现选了"默认可见"（见 mall-portal 的 PmsCommentServiceImpl.create），
 * 适合内部/演示环境；对外产品一般选"先审后发"或"敏感词先过机器、人工只处理异常"。
 *
 * Created 2026/10/08.
 */
@Controller
@Tag(name = "PmsCommentController", description = "商品评价管理（后台）")
@RequestMapping("/comment")
public class PmsCommentController {

    @Autowired
    private PmsCommentService commentService;

    @Operation(summary = "分页查询评价")
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<CommonPage<PmsComment>> list(PmsCommentQueryParam param) {
        List<PmsComment> list = commentService.list(param);
        return CommonResult.success(CommonPage.restPage(list));
    }

    @Operation(summary = "批量修改显示状态（审核）")
    @RequestMapping(value = "/update/showStatus", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> updateShowStatus(@RequestParam("ids") List<Long> ids,
                                                  @RequestParam Integer showStatus) {
        return CommonResult.success(commentService.updateShowStatus(ids, showStatus));
    }

    @Operation(summary = "批量删除评价（连带删除其下回复）")
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> delete(@RequestParam("ids") List<Long> ids) {
        return CommonResult.success(commentService.delete(ids));
    }

    @Operation(summary = "回复评价")
    @RequestMapping(value = "/reply", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> reply(@RequestParam Long commentId,
                                       @RequestParam String content) {
        return CommonResult.success(commentService.reply(commentId, content));
    }

    @Operation(summary = "查看某条评价的回复")
    @RequestMapping(value = "/replay/{commentId}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<List<PmsCommentReplay>> listReplay(@PathVariable Long commentId) {
        return CommonResult.success(commentService.listReplay(commentId));
    }
}
