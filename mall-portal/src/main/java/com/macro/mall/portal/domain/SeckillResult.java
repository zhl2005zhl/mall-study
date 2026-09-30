package com.macro.mall.portal.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 秒杀下单结果。
 *
 * 【为什么要有 status = 0「排队中」这个状态】
 * 秒杀是异步下单：接口返回时订单还没建出来，如果直接返回 success + orderId，
 * 前端拿不到真实订单号；如果同步等到建单完成再返回，就失去了削峰的意义。
 * 所以必须有一个"已受理、处理中"的中间态，让前端拿 requestId 去轮询最终结果。
 * 这与 HTTP 的 202 Accepted 语义一致。
 *
 * Created 2026/09/30.
 */
@Data
public class SeckillResult {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    @Schema(title = "请求唯一标识：异步下单后凭它查询最终结果")
    private String requestId;

    @Schema(title = "0->排队中；1->下单成功；2->下单失败")
    private Integer status;

    @Schema(title = "订单ID（status=1 时才有）")
    private Long orderId;

    @Schema(title = "订单编号（status=1 时才有）")
    private String orderSn;

    @Schema(title = "提示信息：排队中/成功/失败原因")
    private String message;

    public static SeckillResult pending(String requestId) {
        SeckillResult r = new SeckillResult();
        r.setRequestId(requestId);
        r.setStatus(STATUS_PENDING);
        r.setMessage("已进入抢购队列，正在为你生成订单，请稍后查询");
        return r;
    }

    public static SeckillResult failed(String requestId, String message) {
        SeckillResult r = new SeckillResult();
        r.setRequestId(requestId);
        r.setStatus(STATUS_FAILED);
        r.setMessage(message);
        return r;
    }
}
