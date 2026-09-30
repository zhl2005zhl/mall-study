package com.macro.mall.portal.dao;

import com.macro.mall.portal.domain.FlashPromotionOrderRecord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 秒杀下单流水的读写
 *
 * Created 2026/09/30.
 */
public interface FlashPromotionOrderDao {

    /**
     * 插入排队流水。
     *
     * request_id 上有唯一索引 —— 所以「同一请求重复提交」会撞唯一键报错，
     * 调用方捕获后按「已存在」处理即可。**不要先 select 再 insert 来判断重复**，
     * 那是"先判断后执行"，并发下两个请求会都判定为不存在然后一起插。
     */
    int insert(FlashPromotionOrderRecord record);

    FlashPromotionOrderRecord selectByRequestId(@Param("requestId") String requestId);

    /**
     * 统计某会员在某活动×场次×商品下已占用的数量（排队中 + 已建单都算）。
     *
     * 为什么把 status=0（排队中）也算进去：一个请求已经预扣了 Redis 库存、
     * 消息还在队列里没落库，这时如果只统计"已建单"的数量，限购就形同虚设 ——
     * 用户可以在消息落库前把限购数量刷满。
     */
    int countMemberBought(@Param("memberId") Long memberId,
                          @Param("promotionId") Long promotionId,
                          @Param("sessionId") Long sessionId,
                          @Param("productId") Long productId);

    /**
     * 标记建单成功。条件更新：只有当前还是「排队中」才更新，
     * 返回 0 说明这次消费是重复的（已经处理过了），调用方不要重复建单。
     */
    int markSuccess(@Param("id") Long id, @Param("orderId") Long orderId, @Param("orderSn") String orderSn);

    /**
     * 标记失败（重试耗尽后）。只允许从「排队中」迁移，避免把已成功的流水改成失败。
     */
    int markFailed(@Param("id") Long id, @Param("failReason") String failReason);

    /**
     * 查长时间停在「排队中」的流水 —— 对账任务的输入。
     * 正常情况下一张流水在秒级内就会变成成功/失败；超过阈值还是排队中，
     * 说明消息丢了或消费者挂了，需要补偿或人工介入。
     */
    List<FlashPromotionOrderRecord> selectStuckPending(@Param("before") Date before,
                                                       @Param("limit") Integer limit);

    /**
     * 统计某活动×场次下已建单的流水数量（对账用：应与 DB 的 sold_count 对得上）
     */
    int countSuccessInSession(@Param("promotionId") Long promotionId,
                              @Param("sessionId") Long sessionId);

    /**
     * 按订单ID查流水 —— 取消订单时用它判断"这个订单是不是秒杀订单"。
     * 返回 null 表示是普通订单，不需要回滚秒杀库存。
     */
    FlashPromotionOrderRecord selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 标记「已取消并回滚库存」。
     *
     * 条件更新：只有当前 status = 1（已建单）才能迁移到 3。
     * 返回 1 = 本次调用拿到了回滚权，可以放心去回滚库存；
     * 返回 0 = 已经被别的线程回滚过了（重复取消、或超时关单与手动取消并发），必须跳过，
     *          否则会把库存多加一次。
     */
    int markCancelled(@Param("id") Long id);
}
