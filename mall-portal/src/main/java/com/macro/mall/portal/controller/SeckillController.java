package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.domain.FlashPromotionStockInfo;
import com.macro.mall.portal.domain.HomeFlashPromotion;
import com.macro.mall.portal.domain.SeckillRequest;
import com.macro.mall.portal.domain.SeckillResult;
import com.macro.mall.portal.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 秒杀相关接口
 *
 * 【接口清单与各自的输入输出】
 *
 *  1. GET  /seckill/current                      当前秒杀场次与商品（公开）
 *  2. GET  /seckill/products                     指定活动×场次的商品与剩余库存（公开）
 *  3. POST /seckill/order                        提交秒杀（需登录）→ 返回排队中 + requestId
 *  4. GET  /seckill/result/{requestId}           查秒杀结果（需登录）→ 最终成功/失败
 *  5. POST /seckill/warmUp                       库存预热（管理接口，需登录）
 *  6. POST /seckill/reconcile                    手动触发对账（管理接口，需登录）
 *
 * 【异常与错误码约定】
 *   · 参数校验失败 / 活动未开始 / 已抢完 / 超限购 / 触发限流 → 业务异常，返回 code=500 + 明确文案
 *   · 排队中 → code=200 + data.status=0（注意：这不是失败，是"已受理"）
 *   · requestId 查不到或不属于当前用户 → 统一返回"秒杀请求不存在"，不区分两种情况（避免 id 探测）
 *
 * Created 2026/09/30.
 */
@Controller
@Tag(name = "SeckillController", description = "秒杀相关接口")
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    @Operation(summary = "获取当前秒杀场次及商品")
    @RequestMapping(value = "/current", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<HomeFlashPromotion> current() {
        return CommonResult.success(seckillService.getCurrentSeckill());
    }

    @Operation(summary = "查询指定活动场次下的秒杀商品与剩余库存")
    @RequestMapping(value = "/products", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<List<FlashPromotionStockInfo>> products(
            @RequestParam Long flashPromotionId,
            @RequestParam Long flashPromotionSessionId) {
        return CommonResult.success(
                seckillService.listSeckillProducts(flashPromotionId, flashPromotionSessionId));
    }

    @Operation(summary = "提交秒杀下单（异步：立即返回排队中，凭 requestId 查结果）")
    @RequestMapping(value = "/order", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<SeckillResult> order(@Validated @RequestBody SeckillRequest request) {
        return CommonResult.success(seckillService.submit(request));
    }

    @Operation(summary = "查询秒杀结果")
    @RequestMapping(value = "/result/{requestId}", method = RequestMethod.GET)
    @ResponseBody
    public CommonResult<SeckillResult> result(@PathVariable String requestId) {
        return CommonResult.success(seckillService.getResult(requestId));
    }

    @Operation(summary = "预热秒杀库存（force=true 时以数据库为准强制重建 Redis 库存）")
    @RequestMapping(value = "/warmUp", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> warmUp(@RequestParam Long flashPromotionId,
                                        @RequestParam Long flashPromotionSessionId,
                                        @RequestParam(defaultValue = "false") boolean force) {
        return CommonResult.success(seckillService.warmUp(flashPromotionId, flashPromotionSessionId, force));
    }

    @Operation(summary = "手动触发秒杀库存对账（比对 Redis 与数据库，发现分歧则修正 Redis）")
    @RequestMapping(value = "/reconcile", method = RequestMethod.POST)
    @ResponseBody
    public CommonResult<Integer> reconcile(@RequestParam Long flashPromotionId,
                                           @RequestParam Long flashPromotionSessionId) {
        return CommonResult.success(seckillService.reconcile(flashPromotionId, flashPromotionSessionId));
    }
}
