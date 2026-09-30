package com.macro.mall.portal.domain;

import lombok.Data;

import java.util.Date;

/**
 * 秒杀下单流水（对应 sms_flash_promotion_order 表）
 *
 * 这张表承担四件事，缺任何一件异步下单都不成立：
 *   1. 幂等键（request_id 唯一索引）
 *   2. 异步结果查询（前端拿 requestId 轮询）
 *   3. 限购统计（按 member + 活动 + 场次 + 商品 聚合）
 *   4. 对账（status=0 长时间不动的就是异常单）
 *
 * Created 2026/09/30.
 */
@Data
public class FlashPromotionOrderRecord {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
    /**
     * 订单已取消、秒杀库存已回滚。
     *
     * 为什么要单独一个状态而不是复用 FAILED：
     * 「回滚」这个动作只能做一次（回滚两次就等于凭空多出库存）。
     * 把它做成 `UPDATE ... SET status=3 WHERE status=1` 的条件更新，
     * 并发/重复取消时只有一个调用能拿到 affected=1，其余拿到 0 直接跳过 ——
     * 这就是回滚的幂等保证，不需要额外的分布式锁。
     */
    public static final int STATUS_CANCELLED = 3;

    private Long id;
    private String requestId;
    private Long orderId;
    private String orderSn;
    private Long memberId;
    private Long flashPromotionId;
    private Long flashPromotionSessionId;
    private Long relationId;
    private Long productId;
    private Integer quantity;
    /** 0->排队中；1->已建单；2->已失败 */
    private Integer status;
    private String failReason;
    private Date createTime;
    private Date updateTime;
}
