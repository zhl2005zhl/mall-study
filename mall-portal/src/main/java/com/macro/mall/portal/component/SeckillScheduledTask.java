package com.macro.mall.portal.component;

import com.macro.mall.model.SmsFlashPromotion;
import com.macro.mall.model.SmsFlashPromotionSession;
import com.macro.mall.portal.dao.FlashPromotionOrderDao;
import com.macro.mall.portal.dao.SmsFlashPromotionDao;
import com.macro.mall.portal.domain.FlashPromotionOrderRecord;
import com.macro.mall.portal.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 秒杀链路的定时任务：预热、对账、卡单补偿。
 *
 * 为什么这三件事必须由定时任务兜底（而不是只靠接口触发）：
 *   · **预热**：如果忘了在活动开始前预热，Redis 里没有库存 key，
 *     tryDeduct 会返回 RESULT_NOT_WARMED 直接拒绝所有请求 —— 整场秒杀卖不出去。
 *   · **对账**：Redis 与 DB 的分歧不会自己消失（Redis 重启、key 过期、异常回滚），
 *     必须有人定期比对。**没有对账的分布式系统等于没有一致性保证。**
 *   · **卡单补偿**：状态停在「排队中」的流水意味着"预扣了 Redis 库存但没建单"，
 *     不处理的话这部分库存就永久悬空了。
 *
 * Created 2026/09/30.
 */
@Component
public class SeckillScheduledTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillScheduledTask.class);

    /** 流水停在「排队中」超过这个时长就认为异常（正常秒级完成） */
    private static final int STUCK_PENDING_MINUTES = 5;
    /** 一次补偿最多处理多少条，避免单次任务过长 */
    private static final int COMPENSATE_BATCH_SIZE = 100;

    @Autowired
    private SmsFlashPromotionDao flashPromotionDao;
    @Autowired
    private FlashPromotionOrderDao flashPromotionOrderDao;
    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillStockManager stockManager;

    /**
     * 预热任务：每分钟检查一次，把「即将开始 / 正在进行」的场次预热到 Redis。
     *
     * force=false 保证幂等：已经预热过的场次直接跳过，不会把已售库存覆盖回去。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void autoWarmUp() {
        try {
            Date now = new Date();
            SmsFlashPromotion promotion = flashPromotionDao.selectActivePromotion(now);
            if (promotion == null) {
                return;
            }
            // 把所有启用的场次都尝试预热一遍（幂等，已预热的会直接跳过）。
            // 为什么不只预热"当前场次"：用户可能在上一场刚结束时刷新页面，
            // 下一场的秒杀商品也需要有库存数据才能正常展示"即将开始"。
            SmsFlashPromotionSession current = flashPromotionDao.selectActiveSession(now);
            if (current != null) {
                int warmed = stockManager.warmUp(promotion.getId(), current.getId(), false);
                if (warmed > 0) {
                    LOGGER.info("定时预热完成：promotionId={} sessionId={} 商品数={}",
                            promotion.getId(), current.getId(), warmed);
                }
            }
        } catch (Exception e) {
            // 定时任务绝不能因为没有外层 try-catch 而中断 —— 一次异常就再也不会执行了
            LOGGER.error("秒杀定时预热失败", e);
        }
    }

    /**
     * 对账任务：每 5 分钟比对一次 Redis 与 DB 的秒杀剩余库存。
     *
     * 这是"最终一致性"的落点：即使前面所有环节都做了补偿，仍然可能有遗漏
     * （比如 Redis 重启导致数据丢失、进程被 kill 导致补偿代码没跑到）。
     * 对账是最后一道防线，它不依赖任何"正常路径"，只依赖两个存储的最终状态。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void autoReconcile() {
        try {
            Date now = new Date();
            SmsFlashPromotion promotion = flashPromotionDao.selectActivePromotion(now);
            SmsFlashPromotionSession session = flashPromotionDao.selectActiveSession(now);
            if (promotion == null || session == null) {
                return;
            }
            int fixed = seckillService.reconcile(promotion.getId(), session.getId());
            if (fixed > 0) {
                LOGGER.warn("★ 秒杀对账发现并修复了 {} 处库存分歧（promotionId={} sessionId={}）。"
                                + "请检查上方日志确认是超发还是漏卖。",
                        fixed, promotion.getId(), session.getId());
            }
        } catch (Exception e) {
            LOGGER.error("秒杀定时对账失败", e);
        }
    }

    /**
     * 卡单补偿：把长时间停在「排队中」的流水捞出来处理。
     *
     * 出现这种情况通常意味着：消息在 MQ 里丢了、消费者进程挂了没重启、
     * 或者消息进了死信队列没人管。无论哪种，**Redis 里的库存已经被预扣了**，
     * 所以必须把它回滚掉，否则这件商品就少卖了。
     *
     * 注意这里选择「回滚」而不是「重试建单」：
     * 因为超过 5 分钟还没建单的秒杀，用户很可能已经刷新页面/放弃，
     * 强行补建一个订单反而会造成"用户没主动买却多了个待付款订单"。
     * 回滚库存让商品回到池子里，更符合业务预期。
     */
    @Scheduled(cron = "0 */2 * * * ?")
    public void compensateStuckOrders() {
        try {
            Date before = new Date(System.currentTimeMillis() - STUCK_PENDING_MINUTES * 60 * 1000L);
            List<FlashPromotionOrderRecord> stuckList =
                    flashPromotionOrderDao.selectStuckPending(before, COMPENSATE_BATCH_SIZE);
            if (stuckList.isEmpty()) {
                return;
            }
            LOGGER.warn("发现 {} 条卡在「排队中」的秒杀流水，开始回滚库存", stuckList.size());
            for (FlashPromotionOrderRecord record : stuckList) {
                try {
                    int qty = record.getQuantity() == null ? 1 : record.getQuantity();
                    seckillService.rollbackSeckillStock(record.getRelationId(), record.getProductId(),
                            record.getFlashPromotionId(), record.getFlashPromotionSessionId(),
                            record.getMemberId(), qty);
                    flashPromotionOrderDao.markFailed(record.getId(), "排队超时未处理，库存已回滚");
                    LOGGER.warn("已回滚超时卡单：requestId={}", record.getRequestId());
                } catch (Exception e) {
                    // 单条失败不影响其它条
                    LOGGER.error("回滚卡单失败：requestId={}", record.getRequestId(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("秒杀卡单补偿任务失败", e);
        }
    }
}
