package com.macro.mall.portal.dao;

import com.macro.mall.model.SmsFlashPromotion;
import com.macro.mall.model.SmsFlashPromotionSession;
import com.macro.mall.portal.domain.FlashPromotionStockInfo;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 秒杀相关的自定义查询
 *
 * 为什么单独建这个 Dao 而不是用 MBG 生成的 Mapper：
 *   1. 需要「原子扣减 + affected rows 判断」这类手写 SQL，MBG 生成不出来；
 *   2. 需要把关联查询的结果直接映射成一个聚合 DTO（FlashPromotionStockInfo），
 *      用生成的 Example/Criteria 拼不出来，也不该去改生成物（下次重新生成就丢了）。
 *   这与项目里 HomeDao / PortalOrderDao / SmsCouponHistoryDao 的做法一致。
 *
 * Created 2026/09/30.
 */
public interface SmsFlashPromotionDao {

    /**
     * 查当前生效的秒杀活动（status=1 且今天落在起止日期之间）。
     *
     * ★ **这个方法的语义是「现在该展示哪一个活动」，不是「某个活动现在生效吗」。**
     *   因为同一时刻可能有多个活动同时生效，而展示场景必须给出一个确定的答案，
     *   所以它带 LIMIT 1。校验场景请用下面的 selectActivePromotionById。
     */
    SmsFlashPromotion selectActivePromotion(@Param("now") Date now);

    /**
     * ★ 校验用：按 id 查活动，**并判断这个活动自身当前是否生效**。
     *
     * 与上面那个方法的区别是「判断的对象」：
     *   · selectActivePromotion    → 我要挑一个出来展示（LIMIT 1）
     *   · selectActivePromotionById → 这个指定的活动现在生效吗（有就生效、没有就不生效）
     *
     * 用错会导致：请求一个**确实在生效**的活动，却因为「它不是系统挑中的那个」而被拒绝。
     *
     * @return 生效则返回该活动，否则返回 null
     */
    SmsFlashPromotion selectActivePromotionById(@Param("id") Long id, @Param("now") Date now);

    /**
     * 按 id 查场次（用于校验请求里的场次 id 真实存在且启用）
     */
    SmsFlashPromotionSession selectSessionById(@Param("sessionId") Long sessionId);

    /**
     * 查当前时间落在这个时间窗内的场次。
     *
     * ★ 同样地，**语义是「现在该展示哪一个场次」**（ORDER BY start_time DESC LIMIT 1），
     *   不是「某个场次现在生效吗」。校验场景请用 selectActiveSessionById。
     */
    SmsFlashPromotionSession selectActiveSession(@Param("now") Date now);

    /**
     * ★ 校验用：按 id 查场次，**并判断这个场次自身当前是否在时间窗内**。
     *
     * 为什么必须有这个方法：同一时刻可以有两个以上场次同时生效
     * （例如「全天场次 00:00-23:59」与「08:00-10:00 场次」在 09:00 都成立）。
     * 如果用 selectActiveSession 的结果做 `equals` 比较，
     * 用户请求另一个**确实生效**的场次就会被错误拒绝。
     *
     * @return 生效则返回该场次，否则返回 null
     */
    SmsFlashPromotionSession selectActiveSessionById(@Param("id") Long id, @Param("now") Date now);

    /**
     * 查某个活动×场次下的全部秒杀商品库存行（含已售数量）
     */
    List<FlashPromotionStockInfo> selectStockInfoList(@Param("promotionId") Long promotionId,
                                                      @Param("sessionId") Long sessionId);

    /**
     * 查单条秒杀库存行（对账、回滚时定位用）
     */
    FlashPromotionStockInfo selectStockInfo(@Param("relationId") Long relationId);

    /**
     * 按商品查某活动×场次下的秒杀配置（下单前校验商品是否在这个秒杀场次里）
     */
    FlashPromotionStockInfo selectStockInfoByProduct(@Param("promotionId") Long promotionId,
                                                     @Param("sessionId") Long sessionId,
                                                     @Param("productId") Long productId);

    /**
     * DB 侧原子扣减秒杀库存（防超卖的最终兜底）。
     *
     * 条件 flash_promotion_count - flash_promotion_sold_count >= quantity 写在 WHERE 里，
     * 由数据库保证互斥；返回 0 表示剩余不足，调用方必须视为下单失败。
     */
    int lockFlashStock(@Param("relationId") Long relationId, @Param("quantity") Integer quantity);

    /**
     * 回滚秒杀库存（取消订单 / 消费失败补偿）。
     * 带 flash_promotion_sold_count >= quantity 的下限保护，避免扣成负数。
     */
    int releaseFlashStock(@Param("relationId") Long relationId, @Param("quantity") Integer quantity);
}
