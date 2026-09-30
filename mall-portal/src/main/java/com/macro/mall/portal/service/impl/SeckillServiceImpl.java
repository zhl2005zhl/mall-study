package com.macro.mall.portal.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.model.*;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.component.SeckillOrderSender;
import com.macro.mall.portal.component.SeckillRateLimiter;
import com.macro.mall.portal.component.SeckillStockManager;
import com.macro.mall.portal.dao.FlashPromotionOrderDao;
import com.macro.mall.portal.dao.HomeDao;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.dao.PortalOrderItemDao;
import com.macro.mall.portal.dao.SmsFlashPromotionDao;
import com.macro.mall.portal.domain.*;
import com.macro.mall.portal.service.SeckillService;
import com.macro.mall.portal.service.UmsMemberReceiveAddressService;
import com.macro.mall.portal.service.UmsMemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀服务实现。
 *
 * ========================= 整条链路的「三个必须」 =========================
 *   **必须异步**：接口不建单，只预扣库存 + 发消息，立刻返回。
 *   **必须幂等**：MQ 至少一次投递，"重复消费"是常态而不是异常。
 *   **必须可补偿**：任何一步失败，都要把 Redis 预扣的库存还回去；
 *                  还不了的要能通过对账发现。
 *
 * ==================== 关于「不做大事务」这个刻意的设计 ====================
 * submit() 上**故意不加 @Transactional**。原因是这条链路跨了两个存储系统：
 * Redis 预扣和数据库流水。如果给 submit 加事务，会出现这种致命组合：
 * 事务里已经发完 MQ、Redis 也扣完了，事务因为在最后一步回滚 ——
 * 结果是「订单流水没了，但 Redis 库存被扣了，而且消息已经投出去要建单」，
 * 这种"半成功"状态是最难排查的。
 * 所以这里改成**显式分步 + 显式补偿**：每一步失败都由代码明确处理，
 * 而不是把一致性寄托在事务边界上 —— 事务管不到 Redis，这是前提。
 *
 * Created 2026/09/30.
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillServiceImpl.class);

    /** 秒杀订单的超时关单时间（分钟）—— 比普通订单短，因为秒杀库存要尽快释放给下一个人 */
    private static final int SECKILL_ORDER_TIMEOUT_MINUTES = 5;

    @Autowired
    private SmsFlashPromotionDao flashPromotionDao;
    @Autowired
    private HomeDao homeDao;
    @Autowired
    private FlashPromotionOrderDao flashPromotionOrderDao;
    @Autowired
    private SeckillStockManager stockManager;
    @Autowired
    private SeckillRateLimiter rateLimiter;
    @Autowired
    private SeckillOrderSender seckillOrderSender;
    @Autowired
    private CancelOrderSender cancelOrderSender;
    @Autowired
    private UmsMemberService memberService;
    @Autowired
    private UmsMemberReceiveAddressService addressService;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private PortalOrderItemDao orderItemDao;
    @Autowired
    private PortalOrderDao portalOrderDao;
    @Autowired
    private RedisService redisService;
    @Value("${redis.key.orderId}")
    private String REDIS_KEY_ORDER_ID;
    @Value("${redis.database}")
    private String REDIS_DATABASE;

    /* ==================================================================
       ① 活动配置
       ================================================================== */

    @Override
    public HomeFlashPromotion getCurrentSeckill() {
        HomeFlashPromotion result = new HomeFlashPromotion();
        Date now = new Date();
        SmsFlashPromotion promotion = flashPromotionDao.selectActivePromotion(now);
        if (promotion == null) {
            result.setProductList(Collections.emptyList());
            return result;
        }
        SmsFlashPromotionSession session = flashPromotionDao.selectActiveSession(now);
        if (session == null) {
            result.setProductList(Collections.emptyList());
            return result;
        }
        result.setStartTime(session.getStartTime());
        result.setEndTime(session.getEndTime());
        // 复用首页已有的秒杀商品查询，避免出现"两套口径"（同一份数据两处 SQL 各写一遍，
        // 以后改了一处忘了另一处，前台就会出现"首页有货、秒杀页没货"这种诡异现象）
        List<FlashPromotionProduct> products =
                homeDao.getFlashProductList(promotion.getId(), session.getId());
        for (FlashPromotionProduct product : products) {
            // 展示口径修正：HomeDao 查出来的是「配置的总库存」，
            // 而用户关心的是「还剩几件」。这里用 Redis 的实时剩余覆盖掉。
            // 没预热时（返回 null）就保留配置值，不做假数据。
            Integer redisStock = stockManager.getRedisStock(
                    promotion.getId(), session.getId(), product.getId());
            if (redisStock != null) {
                product.setFlashPromotionCount(redisStock);
            }
        }
        result.setProductList(products);
        return result;
    }

    @Override
    public List<FlashPromotionStockInfo> listSeckillProducts(Long promotionId, Long sessionId) {
        List<FlashPromotionStockInfo> list = flashPromotionDao.selectStockInfoList(promotionId, sessionId);
        for (FlashPromotionStockInfo info : list) {
            // 展示口径：优先用 Redis 的实时剩余；没预热时回落到 DB 的权威值
            Integer redisStock = stockManager.getRedisStock(promotionId, sessionId, info.getProductId());
            info.setFlashPromotionCount(redisStock != null ? redisStock : info.getRemainCount());
        }
        return list;
    }

    /* ==================================================================
       ② 库存预热
       ================================================================== */

    @Override
    public int warmUp(Long promotionId, Long sessionId, boolean force) {
        SmsFlashPromotion promotion = flashPromotionDao.selectActivePromotion(new Date());
        if (promotion == null || !promotion.getId().equals(promotionId)) {
            Asserts.fail("秒杀活动不存在或已结束");
        }
        SmsFlashPromotionSession session = flashPromotionDao.selectSessionById(sessionId);
        if (session == null) {
            Asserts.fail("秒杀场次不存在或已停用");
        }
        return stockManager.warmUp(promotionId, sessionId, force);
    }

    /* ==================================================================
       ③④ 提交秒杀下单：限流 → 预扣 → 落流水 → 发 MQ
       ================================================================== */

    @Override
    public SeckillResult submit(SeckillRequest request) {
        UmsMember member = memberService.getCurrentMember();
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity <= 0) {
            Asserts.fail("秒杀数量必须大于 0");
        }

        // ---------- 1. 校验活动与场次是否真的在生效（绝不信前端传的值） ----------
        Date now = new Date();
        SmsFlashPromotion promotion = flashPromotionDao.selectActivePromotion(now);
        if (promotion == null || !promotion.getId().equals(request.getFlashPromotionId())) {
            Asserts.fail("秒杀活动不存在或已结束");
        }
        SmsFlashPromotionSession activeSession = flashPromotionDao.selectActiveSession(now);
        if (activeSession == null || !activeSession.getId().equals(request.getFlashPromotionSessionId())) {
            // 关键：校验「当前时间确实落在请求的这个场次里」。
            // 否则用户可以传一个已经结束（库存还没清）的场次 id 来绕过活动时间限制。
            Asserts.fail("当前不在该秒杀场次时间内");
        }

        // ---------- 2. 查秒杀商品配置（价格、库存、限购都从这里取，不信任前端） ----------
        FlashPromotionStockInfo stockInfo = flashPromotionDao.selectStockInfoByProduct(
                request.getFlashPromotionId(), request.getFlashPromotionSessionId(), request.getProductId());
        if (stockInfo == null) {
            Asserts.fail("该商品不在本场秒杀活动中");
        }
        int limit = stockInfo.getFlashPromotionLimit() == null ? 1 : stockInfo.getFlashPromotionLimit();
        if (quantity > limit) {
            Asserts.fail("超过每人限购数量：" + limit);
        }

        // ---------- 3. 两层限流（扣库存之前，先挡掉无效流量） ----------
        if (!rateLimiter.tryAcquireByUser(member.getId(),
                request.getFlashPromotionId(), request.getFlashPromotionSessionId(), request.getProductId())) {
            Asserts.fail("操作过于频繁，请稍后再试");
        }
        if (!rateLimiter.tryAcquireByProduct(
                request.getFlashPromotionId(), request.getFlashPromotionSessionId(), request.getProductId())) {
            Asserts.fail("当前抢购人数过多，请稍后再试");
        }

        // ---------- 4. 落流水（拿幂等键） ----------
        // 生成规则：会员+活动+场次+商品+毫秒时间戳+随机数。
        // 不用 UUID 是为了让 requestId 自带定位信息，排查时一眼能看出是谁的哪个请求。
        String requestId = buildRequestId(member.getId(), request);
        FlashPromotionOrderRecord record = new FlashPromotionOrderRecord();
        record.setRequestId(requestId);
        record.setMemberId(member.getId());
        record.setFlashPromotionId(request.getFlashPromotionId());
        record.setFlashPromotionSessionId(request.getFlashPromotionSessionId());
        record.setRelationId(stockInfo.getRelationId());
        record.setProductId(request.getProductId());
        record.setQuantity(quantity);
        record.setStatus(FlashPromotionOrderRecord.STATUS_PENDING);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        try {
            flashPromotionOrderDao.insert(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // request_id 唯一索引撞了 —— 说明这个请求已经提交过，属于重复提交，直接返回原流水状态
            FlashPromotionOrderRecord exist = flashPromotionOrderDao.selectByRequestId(requestId);
            LOGGER.info("秒杀请求重复提交，返回已有流水。requestId={}", requestId);
            return toResult(exist);
        }

        // ---------- 5. Redis 原子预扣 ----------
        long code = stockManager.tryDeduct(request.getFlashPromotionId(),
                request.getFlashPromotionSessionId(), request.getProductId(),
                member.getId(), quantity, limit);
        if (code != SeckillStockManager.RESULT_SUCCESS) {
            String reason = switch ((int) code) {
                case (int) SeckillStockManager.RESULT_OUT_OF_STOCK -> "已被抢完";
                case (int) SeckillStockManager.RESULT_LIMIT_EXCEEDED -> "超过每人限购数量";
                default -> "秒杀尚未开始或已结束";
            };
            // 预扣失败不是系统故障，是正常业务结果：标记流水失败即可，不需要补偿
            flashPromotionOrderDao.markFailed(record.getId(), reason);
            Asserts.fail("抢购失败：" + reason);
        }

        // ---------- 6. 组装消息（含商品与收货信息快照）并投递 ----------
        try {
            SeckillOrderMessage message = buildMessage(request, stockInfo, member, quantity, requestId);
            seckillOrderSender.sendSeckillOrder(message);
        } catch (Exception e) {
            // 【关键补偿】消息发不出去，必须把刚预扣的库存还回去，否则这件商品永远卖不掉。
            // 这说明「预扣」和「投递」必须成对处理 —— 是链路里最容易漏的一处。
            LOGGER.error("秒杀消息投递失败，回滚 Redis 预扣。requestId={}", requestId, e);
            stockManager.release(request.getFlashPromotionId(), request.getFlashPromotionSessionId(),
                    request.getProductId(), member.getId(), quantity, stockInfo.getFlashPromotionCount());
            flashPromotionOrderDao.markFailed(record.getId(), "消息投递失败：" + e.getMessage());
            Asserts.fail("抢购请求提交失败，请重试");
        }

        return SeckillResult.pending(requestId);
    }

    /* ==================================================================
       ⑤ 消费者：真正建单
       ================================================================== */

    @Override
    public void handleSeckillOrder(SeckillOrderMessage message) {
        // ---------- 1. 幂等：查流水状态 ----------
        FlashPromotionOrderRecord record = flashPromotionOrderDao.selectByRequestId(message.getRequestId());
        if (record == null) {
            // 流水不存在说明是脏消息（可能是历史遗留或人工投递）
            // 抛出让它进死信队列，不在这里默默吞掉 —— 它背后可能占着 Redis 库存
            throw new IllegalStateException("秒杀流水不存在，requestId=" + message.getRequestId());
        }
        if (record.getStatus() != FlashPromotionOrderRecord.STATUS_PENDING) {
            // 已经处理过（成功或失败）—— 这是重复消费，直接返回即可，这就是幂等
            LOGGER.info("秒杀流水已处理过，跳过。requestId={} status={}",
                    message.getRequestId(), record.getStatus());
            return;
        }

        int quantity = record.getQuantity() == null ? 1 : record.getQuantity();

        // ---------- 2. DB 侧兜底扣减（防超卖的第二道防线） ----------
        int locked = flashPromotionDao.lockFlashStock(record.getRelationId(), quantity);
        if (locked == 0) {
            // DB 说没了 —— 说明 Redis 和 DB 的库存已经不一致（Redis 更宽松）。
            // 这是**业务失败而非系统故障**：不该重试，直接标记失败 + 回滚 Redis 预扣 + 正常返回（消息被 ack）。
            LOGGER.warn("DB 侧秒杀库存不足，判定抢购失败并回滚 Redis。requestId={} relationId={}",
                    message.getRequestId(), record.getRelationId());
            rollbackSeckillStock(record.getRelationId(), record.getProductId(),
                    record.getFlashPromotionId(), record.getFlashPromotionSessionId(),
                    record.getMemberId(), quantity);
            flashPromotionOrderDao.markFailed(record.getId(), "库存不足（DB 兜底校验未通过）");
            return;
        }

        // ---------- 3. DB 侧限购兜底校验 ----------
        // 为什么要再查一次：Redis 的限购记录可能因为重启/TTL 过期而丢失，
        // 而 DB 的流水是持久的。这里用流水表统计"已占用数量"（含排队中）作为最终口径。
        int bought = flashPromotionOrderDao.countMemberBought(
                record.getMemberId(), record.getFlashPromotionId(),
                record.getFlashPromotionSessionId(), record.getProductId());
        FlashPromotionStockInfo stockInfo = flashPromotionDao.selectStockInfo(record.getRelationId());
        int limit = (stockInfo == null || stockInfo.getFlashPromotionLimit() == null)
                ? 1 : stockInfo.getFlashPromotionLimit();
        // 注意：当前这条流水自身也算在 bought 里（status=0 会被统计），所以阈值用 ">" 而不是 ">="
        if (bought > limit) {
            LOGGER.warn("DB 侧限购校验未通过，判定失败。requestId={} bought={} limit={}",
                    message.getRequestId(), bought, limit);
            flashPromotionDao.releaseFlashStock(record.getRelationId(), quantity);
            rollbackSeckillStock(record.getRelationId(), record.getProductId(),
                    record.getFlashPromotionId(), record.getFlashPromotionSessionId(),
                    record.getMemberId(), quantity);
            flashPromotionOrderDao.markFailed(record.getId(), "超过每人限购数量");
            return;
        }

        // ---------- 4. 锁真实库存（SKU 维度，复用已有的原子方法） ----------
        int skuLocked = skuStockMapper.lockSkuStock(message.getProductSkuId(), quantity);
        if (skuLocked == 0) {
            // 秒杀库存有，但商品真实库存没了 —— 同样是业务失败
            LOGGER.warn("秒杀成功但商品真实库存不足，判定失败。requestId={} skuId={}",
                    message.getRequestId(), message.getProductSkuId());
            flashPromotionDao.releaseFlashStock(record.getRelationId(), quantity);
            rollbackSeckillStock(record.getRelationId(), record.getProductId(),
                    record.getFlashPromotionId(), record.getFlashPromotionSessionId(),
                    record.getMemberId(), quantity);
            flashPromotionOrderDao.markFailed(record.getId(), "商品库存不足");
            return;
        }

        // ---------- 5. 建单（真正的落库） ----------
        // 从这里开始如果抛异常，Spring AMQP 会重试；重试时第 1 步会看到流水仍是「排队中」，
        // 但 DB 的 sold_count 和 SKU lock_stock 已经被扣过一次了 —— 所以下面的写库
        // 必须整体成功或整体失败，不能出现"扣了库存但没建单"。
        // 这里用 try-catch 保证：建单失败时把已经扣掉的库存全部还原，然后重新抛出交给重试。
        OmsOrder order = null;
        try {
            order = createOrder(message, quantity);
            flashPromotionOrderDao.markSuccess(record.getId(), order.getId(), order.getOrderSn());
        } catch (Exception e) {
            LOGGER.error("秒杀建单失败，回滚已扣库存。requestId={}", message.getRequestId(), e);
            flashPromotionDao.releaseFlashStock(record.getRelationId(), quantity);
            rollbackSeckillStock(record.getRelationId(), record.getProductId(),
                    record.getFlashPromotionId(), record.getFlashPromotionSessionId(),
                    record.getMemberId(), quantity);
            portalOrderDao.releaseSkuStockLock(buildReleaseItems(message));
            throw e;   // 抛出 → 交给 Spring AMQP 重试 / 进死信队列
        }

        // ---------- 6. 发送超时关单延迟消息（⑥ 环节的入口） ----------
        try {
            cancelOrderSender.sendMessage(order.getId(), SECKILL_ORDER_TIMEOUT_MINUTES * 60 * 1000L);
        } catch (Exception e) {
            // 发不出延迟消息不影响下单成功 —— 还有 OrderTimeOutCancelTask 定时任务兜底扫描超时订单，
            // 所以这里只告警、不抛异常（不能因为一个非关键路径的失败把已经成功的订单判为失败）
            LOGGER.error("秒杀订单延迟关单消息发送失败，将由定时任务兜底。orderId={}", order.getId(), e);
        }

        LOGGER.info("秒杀下单成功：requestId={} orderId={} orderSn={}",
                message.getRequestId(), order.getId(), order.getOrderSn());
    }

    /* ==================================================================
       结果查询
       ================================================================== */

    @Override
    public SeckillResult getResult(String requestId) {
        FlashPromotionOrderRecord record = flashPromotionOrderDao.selectByRequestId(requestId);
        if (record == null) {
            Asserts.fail("秒杀请求不存在");
        }
        // 归属校验：只能查自己的请求
        UmsMember member = memberService.getCurrentMember();
        if (!member.getId().equals(record.getMemberId())) {
            Asserts.fail("秒杀请求不存在");
        }
        return toResult(record);
    }

    /* ==================================================================
       ⑥ 回滚库存
       ================================================================== */

    @Override
    public void rollbackSeckillStock(Long relationId, Long productId, Long flashPromotionId,
                                     Long flashPromotionSessionId, Long memberId, int quantity) {
        // DB 侧回滚（带下限保护，重复回滚不会扣成负数）
        int affected = flashPromotionDao.releaseFlashStock(relationId, quantity);
        if (affected == 0) {
            // 说明 sold_count 已经不够扣了 —— 通常是重复回滚。只告警，不抛异常：
            // 回滚是补偿动作，不该因为"已经回滚过"而让上层的取消流程也失败。
            LOGGER.warn("秒杀库存回滚未生效（可能已回滚过）：relationId={} qty={}", relationId, quantity);
        }
        // Redis 侧回滚
        FlashPromotionStockInfo info = flashPromotionDao.selectStockInfo(relationId);
        int total = info == null || info.getFlashPromotionCount() == null ? Integer.MAX_VALUE
                : info.getFlashPromotionCount();
        stockManager.release(flashPromotionId, flashPromotionSessionId, productId, memberId, quantity, total);
    }

    /* ==================================================================
       ⑦ 最终一致性校验
       ================================================================== */

    @Override
    public int reconcile(Long promotionId, Long sessionId) {
        List<FlashPromotionStockInfo> list = flashPromotionDao.selectStockInfoList(promotionId, sessionId);
        int fixed = 0;
        for (FlashPromotionStockInfo info : list) {
            int dbRemain = info.getRemainCount();
            Integer redisRemain = stockManager.getRedisStock(promotionId, sessionId, info.getProductId());

            if (redisRemain == null) {
                // Redis 里没有这个商品的库存 —— 要么没预热，要么 key 过期了。
                // 修法：以 DB 为准重建。
                LOGGER.warn("对账发现 Redis 缺少秒杀库存，按 DB 重建。relationId={} productId={} dbRemain={}",
                        info.getRelationId(), info.getProductId(), dbRemain);
                stockManager.forceResetStock(promotionId, sessionId, info.getProductId(), dbRemain);
                fixed++;
                continue;
            }
            if (redisRemain == dbRemain) {
                continue;
            }

            // 出现分歧。**一律以 DB 为准**，因为 DB 的 sold_count 是落库的、持久的，
            // 而 Redis 可能因为重启、过期、误操作而失真。
            //
            // 但要区分方向，因为两个方向的严重性完全不同：
            //   · Redis 剩余 > DB 剩余：**会超卖**，必须立刻修正（最危险）
            //   · Redis 剩余 < DB 剩余：**会漏卖**，影响较小，但也修掉
            if (redisRemain > dbRemain) {
                LOGGER.error("★ 对账发现秒杀库存超发风险（Redis 比 DB 多 {} 件），已强制修正。"
                                + "promotionId={} sessionId={} productId={} redis={} db={}",
                        redisRemain - dbRemain, promotionId, sessionId, info.getProductId(), redisRemain, dbRemain);
            } else {
                LOGGER.warn("对账发现秒杀库存漏卖（Redis 比 DB 少 {} 件），已修正。"
                                + "promotionId={} sessionId={} productId={} redis={} db={}",
                        dbRemain - redisRemain, promotionId, sessionId, info.getProductId(), redisRemain, dbRemain);
            }
            stockManager.forceResetStock(promotionId, sessionId, info.getProductId(), dbRemain);
            fixed++;
        }
        return fixed;
    }

    /* ==================================================================
       内部方法
       ================================================================== */

    private String buildRequestId(Long memberId, SeckillRequest request) {
        return "SK" + memberId + "-" + request.getFlashPromotionId() + "-" + request.getFlashPromotionSessionId()
                + "-" + request.getProductId() + "-" + System.currentTimeMillis()
                + "-" + ThreadLocalRandom.current().nextInt(100000);
    }

    private SeckillResult toResult(FlashPromotionOrderRecord record) {
        SeckillResult result = new SeckillResult();
        result.setRequestId(record.getRequestId());
        result.setStatus(record.getStatus());
        result.setOrderId(record.getOrderId());
        result.setOrderSn(record.getOrderSn());
        switch (record.getStatus()) {
            case FlashPromotionOrderRecord.STATUS_PENDING -> result.setMessage("已进入抢购队列，请稍后查询");
            case FlashPromotionOrderRecord.STATUS_SUCCESS -> result.setMessage("抢购成功，请尽快支付");
            default -> result.setMessage("抢购失败：" + record.getFailReason());
        }
        return result;
    }

    /**
     * 组装消息：把建单需要的一切都拍成快照塞进去。
     * 消费者没有用户上下文（SecurityContext 是请求线程的），所以收货信息必须在提交时就取好。
     */
    private SeckillOrderMessage buildMessage(SeckillRequest request, FlashPromotionStockInfo stockInfo,
                                             UmsMember member, int quantity, String requestId) {
        PmsProduct product = productMapper.selectByPrimaryKey(request.getProductId());
        if (product == null) {
            throw new IllegalStateException("商品不存在：" + request.getProductId());
        }
        PmsSkuStock sku = pickSku(request.getProductId());
        if (sku == null) {
            throw new IllegalStateException("商品没有可售规格：" + request.getProductId());
        }
        UmsMemberReceiveAddress address = addressService.getItem(request.getMemberReceiveAddressId());
        if (address == null) {
            throw new IllegalStateException("收货地址不存在：" + request.getMemberReceiveAddressId());
        }

        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setRequestId(requestId);
        message.setMemberId(member.getId());
        message.setMemberUsername(member.getUsername());
        message.setFlashPromotionId(request.getFlashPromotionId());
        message.setFlashPromotionSessionId(request.getFlashPromotionSessionId());
        message.setRelationId(stockInfo.getRelationId());
        message.setProductId(product.getId());
        message.setProductName(product.getName());
        message.setProductPic(product.getPic());
        message.setProductSn(product.getProductSn());
        message.setProductSkuId(sku.getId());
        message.setProductSkuCode(sku.getSkuCode());
        message.setProductCategoryId(product.getProductCategoryId());
        message.setSeckillPrice(stockInfo.getFlashPromotionPrice());
        message.setQuantity(quantity);
        message.setMemberReceiveAddressId(address.getId());
        message.setReceiverName(address.getName());
        message.setReceiverPhone(address.getPhoneNumber());
        message.setReceiverPostCode(address.getPostCode());
        message.setReceiverProvince(address.getProvince());
        message.setReceiverCity(address.getCity());
        message.setReceiverRegion(address.getRegion());
        message.setReceiverDetailAddress(address.getDetailAddress());
        message.setPayType(request.getPayType());
        message.setCreateTime(new Date());
        return message;
    }

    /**
     * 选一个 SKU。
     *
     * 【已知简化】秒杀商品如果有多个规格（颜色/内存），这里固定取 id 最小的那个。
     * 完整的做法是让前端在秒杀请求里带 skuId，并在 Redis 里按 SKU 维度预热库存。
     * 这里简化是因为种子数据里秒杀商品都是"单规格占位"，先保证主链路正确；
     * 方案写进文档，需要时按这个方向扩展即可。
     */
    private PmsSkuStock pickSku(Long productId) {
        PmsSkuStockExample example = new PmsSkuStockExample();
        example.createCriteria().andProductIdEqualTo(productId);
        example.setOrderByClause("id asc");
        List<PmsSkuStock> list = skuStockMapper.selectByExample(example);
        return CollUtil.isEmpty(list) ? null : list.get(0);
    }

    /**
     * 建单：写 oms_order + oms_order_item，并把「锁定的真实库存」转成「已扣减」。
     * 注意价格用的是**秒杀价**（这是台账 #13 的核心诉求：展示与成交必须一致）。
     */
    private OmsOrder createOrder(SeckillOrderMessage message, int quantity) {
        BigDecimal seckillPrice = message.getSeckillPrice();
        BigDecimal totalAmount = seckillPrice.multiply(new BigDecimal(quantity));

        OmsOrder order = new OmsOrder();
        order.setMemberId(message.getMemberId());
        order.setMemberUsername(message.getMemberUsername());
        order.setCreateTime(new Date());
        order.setPayType(message.getPayType());
        order.setSourceType(1);
        order.setStatus(0);              // 待付款
        order.setOrderType(1);           // 1->秒杀订单（这个字段终于被用上了）
        order.setConfirmStatus(0);
        order.setDeleteStatus(0);
        order.setReceiverName(message.getReceiverName());
        order.setReceiverPhone(message.getReceiverPhone());
        order.setReceiverPostCode(message.getReceiverPostCode());
        order.setReceiverProvince(message.getReceiverProvince());
        order.setReceiverCity(message.getReceiverCity());
        order.setReceiverRegion(message.getReceiverRegion());
        order.setReceiverDetailAddress(message.getReceiverDetailAddress());
        order.setTotalAmount(totalAmount);
        order.setFreightAmount(new BigDecimal(0));
        order.setPromotionAmount(new BigDecimal(0));
        order.setCouponAmount(new BigDecimal(0));
        order.setIntegrationAmount(new BigDecimal(0));
        order.setDiscountAmount(new BigDecimal(0));
        order.setPayAmount(totalAmount);
        order.setIntegration(0);
        order.setGrowth(0);
        order.setPromotionInfo("秒杀活动");
        order.setOrderSn(generateOrderSn());
        orderMapper.insert(order);

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(order.getId());
        item.setOrderSn(order.getOrderSn());
        item.setProductId(message.getProductId());
        item.setProductName(message.getProductName());
        item.setProductPic(message.getProductPic());
        item.setProductSn(message.getProductSn());
        item.setProductSkuId(message.getProductSkuId());
        item.setProductSkuCode(message.getProductSkuCode());
        item.setProductCategoryId(message.getProductCategoryId());
        item.setProductPrice(seckillPrice);
        item.setProductQuantity(quantity);
        item.setPromotionAmount(new BigDecimal(0));
        item.setCouponAmount(new BigDecimal(0));
        item.setIntegrationAmount(new BigDecimal(0));
        item.setRealAmount(totalAmount);
        item.setPromotionName("秒杀价");
        item.setGiftIntegration(0);
        item.setGiftGrowth(0);
        orderItemDao.insertList(Collections.singletonList(item));

        // 把「锁定库存」转成「真实扣减」：stock - qty、lock_stock - qty
        List<OmsOrderItem> items = new ArrayList<>();
        items.add(item);
        portalOrderDao.updateSkuStock(items);
        return order;
    }

    /** 建单失败时用来还原 SKU 锁定库存的入参 */
    private List<OmsOrderItem> buildReleaseItems(SeckillOrderMessage message) {
        OmsOrderItem item = new OmsOrderItem();
        item.setProductSkuId(message.getProductSkuId());
        item.setProductQuantity(message.getQuantity());
        return Collections.singletonList(item);
    }

    /**
     * 生成订单号：与普通订单保持同一套规则（8位日期 + 2位来源 + 2位支付方式 + 递增序号），
     * 用 Redis INCR 保证并发下不重复。
     */
    private String generateOrderSn() {
        StringBuilder sb = new StringBuilder();
        String date = new SimpleDateFormat("yyyyMMdd").format(new Date());
        Long increment = redisService.incr(REDIS_DATABASE + ":" + REDIS_KEY_ORDER_ID + date, 1);
        sb.append(date);
        sb.append(String.format("%02d", 1));
        sb.append(String.format("%02d", 1));
        String inc = increment.toString();
        sb.append(inc.length() <= 6 ? String.format("%06d", increment) : inc);
        return sb.toString();
    }
}
