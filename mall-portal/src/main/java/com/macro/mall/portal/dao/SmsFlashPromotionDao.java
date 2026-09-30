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
     * 查当前生效的秒杀活动（status=1 且今天落在起止日期之间）
     */
    SmsFlashPromotion selectActivePromotion(@Param("now") Date now);

    /**
     * 按 id 查场次（用于校验请求里的场次 id 真实存在且启用）
     */
    SmsFlashPromotionSession selectSessionById(@Param("sessionId") Long sessionId);

    /**
     * 查当前时间落在这个时间窗内的场次
     */
    SmsFlashPromotionSession selectActiveSession(@Param("now") Date now);

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
