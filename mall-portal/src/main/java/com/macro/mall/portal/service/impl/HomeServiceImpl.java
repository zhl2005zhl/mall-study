package com.macro.mall.portal.service.impl;

import com.github.pagehelper.PageHelper;
import com.macro.mall.common.constant.CacheKeys;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.HomeDao;
import com.macro.mall.portal.domain.FlashPromotionProduct;
import com.macro.mall.portal.domain.HomeContentResult;
import com.macro.mall.portal.domain.HomeFlashPromotion;
import com.macro.mall.portal.service.HomeService;
import com.macro.mall.portal.util.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 首页内容管理Service实现类
 * Created by macro on 2019/1/28.
 */
@Service
public class HomeServiceImpl implements HomeService {

    /** 首页缓存基础 TTL（秒）。设短一点：运营在后台改了首页素材，最多 60 秒前台就能看到 */
    private static final long HOME_CONTENT_TTL = 60;
    /** 防雪崩：在基础 TTL 上叠加的随机抖动上限（秒）*/
    private static final int TTL_JITTER_BOUND = 30;
    /** 空结果的兜底 TTL（秒）。短一些，等运营把数据补上后能尽快恢复正常内容 */
    private static final long HOME_CONTENT_EMPTY_TTL = 5;

    @Autowired
    private RedisService redisService;
    @Autowired
    private SmsHomeAdvertiseMapper advertiseMapper;
    @Autowired
    private HomeDao homeDao;
    @Autowired
    private SmsFlashPromotionMapper flashPromotionMapper;
    @Autowired
    private SmsFlashPromotionSessionMapper promotionSessionMapper;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private PmsProductCategoryMapper productCategoryMapper;
    @Autowired
    private CmsSubjectMapper subjectMapper;

    @Override
    public HomeContentResult content() {
        // ① cache-aside：先查缓存。命中就直接返回，完全不打数据库
        Object cached = redisService.get(CacheKeys.HOME_CONTENT);
        if (cached instanceof HomeContentResult) {
            return (HomeContentResult) cached;
        }

        // ② 防击穿：缓存刚失效的那一瞬间，如果放任所有并发请求都去回源，
        //    数据库会被同一个查询同时打穿。这里只放一个线程进去回源，其余线程等它填好缓存。
        //    单机部署用 synchronized 足够；多实例部署要换成 Redis 分布式锁（SETNX + 过期时间）。
        synchronized (this) {
            // 双重检查：等锁的这段时间里，可能已经有别的线程把缓存填好了
            cached = redisService.get(CacheKeys.HOME_CONTENT);
            if (cached instanceof HomeContentResult) {
                return (HomeContentResult) cached;
            }

            HomeContentResult result = buildContent();

            if (isEmpty(result)) {
                // ③ 防穿透（兜底）：回源结果为空时也写缓存，但用很短的 TTL。
                //    否则「查不到 → 每次请求都回源 → 还是查不到」会把数据库一直打着。
                redisService.set(CacheKeys.HOME_CONTENT, result, HOME_CONTENT_EMPTY_TTL);
            } else {
                // ④ 防雪崩：TTL 加随机抖动。固定 TTL 会让大批 key 在同一秒集中失效，
                //    那一秒所有请求同时回源，数据库出现尖刺。
                long ttl = HOME_CONTENT_TTL + ThreadLocalRandom.current().nextInt(TTL_JITTER_BOUND);
                redisService.set(CacheKeys.HOME_CONTENT, result, ttl);
            }
            return result;
        }
    }

    /**
     * 原来那 9 条查询的聚合逻辑，抽出来单独放。
     * 只有缓存未命中时才会被执行 —— 这是这次改造真正省下来的开销。
     */
    private HomeContentResult buildContent() {
        HomeContentResult result = new HomeContentResult();
        //获取首页广告
        result.setAdvertiseList(getHomeAdvertiseList());
        //获取推荐品牌
        result.setBrandList(homeDao.getRecommendBrandList(0,6));
        //获取秒杀信息
        result.setHomeFlashPromotion(getHomeFlashPromotion());
        //获取新品推荐
        result.setNewProductList(homeDao.getNewProductList(0,4));
        //获取人气推荐
        result.setHotProductList(homeDao.getHotProductList(0,4));
        //获取推荐专题
        result.setSubjectList(homeDao.getRecommendSubjectList(0,4));
        return result;
    }

    /**
     * 判断首页聚合结果是不是「什么都没有」。
     * 注意判断的是内容为空，而不是对象为 null —— 这个接口永远返回一个非 null 的对象。
     */
    private boolean isEmpty(HomeContentResult result) {
        if (result == null) {
            return true;
        }
        boolean noFlashProduct = result.getHomeFlashPromotion() == null
                || CollectionUtils.isEmpty(result.getHomeFlashPromotion().getProductList());
        return CollectionUtils.isEmpty(result.getAdvertiseList())
                && CollectionUtils.isEmpty(result.getBrandList())
                && CollectionUtils.isEmpty(result.getNewProductList())
                && CollectionUtils.isEmpty(result.getHotProductList())
                && CollectionUtils.isEmpty(result.getSubjectList())
                && noFlashProduct;
    }

    @Override
    public List<PmsProduct> recommendProductList(Integer pageSize, Integer pageNum) {
        // TODO: 2019/1/29 暂时默认推荐所有商品
        PageHelper.startPage(pageNum,pageSize);
        PmsProductExample example = new PmsProductExample();
        example.createCriteria()
                .andDeleteStatusEqualTo(0)
                .andPublishStatusEqualTo(1);
        return productMapper.selectByExample(example);
    }

    @Override
    public List<PmsProductCategory> getProductCateList(Long parentId) {
        PmsProductCategoryExample example = new PmsProductCategoryExample();
        example.createCriteria()
                .andShowStatusEqualTo(1)
                .andParentIdEqualTo(parentId);
        example.setOrderByClause("sort desc");
        return productCategoryMapper.selectByExample(example);
    }

    @Override
    public List<CmsSubject> getSubjectList(Long cateId, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum,pageSize);
        CmsSubjectExample example = new CmsSubjectExample();
        CmsSubjectExample.Criteria criteria = example.createCriteria();
        criteria.andShowStatusEqualTo(1);
        if(cateId!=null){
            criteria.andCategoryIdEqualTo(cateId);
        }
        return subjectMapper.selectByExample(example);
    }

    @Override
    public List<PmsProduct> hotProductList(Integer pageNum, Integer pageSize) {
        int offset = pageSize * (pageNum - 1);
        return homeDao.getHotProductList(offset, pageSize);
    }

    @Override
    public List<PmsProduct> newProductList(Integer pageNum, Integer pageSize) {
        int offset = pageSize * (pageNum - 1);
        return homeDao.getNewProductList(offset, pageSize);
    }

    private HomeFlashPromotion getHomeFlashPromotion() {
        HomeFlashPromotion homeFlashPromotion = new HomeFlashPromotion();
        //获取当前秒杀活动
        Date now = new Date();
        SmsFlashPromotion flashPromotion = getFlashPromotion(now);
        if (flashPromotion != null) {
            //获取当前秒杀场次
            SmsFlashPromotionSession flashPromotionSession = getFlashPromotionSession(now);
            if (flashPromotionSession != null) {
                homeFlashPromotion.setStartTime(flashPromotionSession.getStartTime());
                homeFlashPromotion.setEndTime(flashPromotionSession.getEndTime());
                //获取下一个秒杀场次
                SmsFlashPromotionSession nextSession = getNextFlashPromotionSession(homeFlashPromotion.getStartTime());
                if(nextSession!=null){
                    homeFlashPromotion.setNextStartTime(nextSession.getStartTime());
                    homeFlashPromotion.setNextEndTime(nextSession.getEndTime());
                }
                //获取秒杀商品
                List<FlashPromotionProduct> flashProductList = homeDao.getFlashProductList(flashPromotion.getId(), flashPromotionSession.getId());
                homeFlashPromotion.setProductList(flashProductList);
            }
        }
        return homeFlashPromotion;
    }

    //获取下一个场次信息
    private SmsFlashPromotionSession getNextFlashPromotionSession(Date date) {
        SmsFlashPromotionSessionExample sessionExample = new SmsFlashPromotionSessionExample();
        sessionExample.createCriteria()
                .andStartTimeGreaterThan(date);
        sessionExample.setOrderByClause("start_time asc");
        List<SmsFlashPromotionSession> promotionSessionList = promotionSessionMapper.selectByExample(sessionExample);
        if (!CollectionUtils.isEmpty(promotionSessionList)) {
            return promotionSessionList.get(0);
        }
        return null;
    }

    private List<SmsHomeAdvertise> getHomeAdvertiseList() {
        SmsHomeAdvertiseExample example = new SmsHomeAdvertiseExample();
        example.createCriteria().andTypeEqualTo(1).andStatusEqualTo(1);
        example.setOrderByClause("sort desc");
        return advertiseMapper.selectByExample(example);
    }

    //根据时间获取秒杀活动
    private SmsFlashPromotion getFlashPromotion(Date date) {
        Date currDate = DateUtil.getDate(date);
        SmsFlashPromotionExample example = new SmsFlashPromotionExample();
        example.createCriteria()
                .andStatusEqualTo(1)
                .andStartDateLessThanOrEqualTo(currDate)
                .andEndDateGreaterThanOrEqualTo(currDate);
        List<SmsFlashPromotion> flashPromotionList = flashPromotionMapper.selectByExample(example);
        if (!CollectionUtils.isEmpty(flashPromotionList)) {
            return flashPromotionList.get(0);
        }
        return null;
    }

    //根据时间获取秒杀场次
    private SmsFlashPromotionSession getFlashPromotionSession(Date date) {
        Date currTime = DateUtil.getTime(date);
        SmsFlashPromotionSessionExample sessionExample = new SmsFlashPromotionSessionExample();
        sessionExample.createCriteria()
                .andStartTimeLessThanOrEqualTo(currTime)
                .andEndTimeGreaterThanOrEqualTo(currTime);
        List<SmsFlashPromotionSession> promotionSessionList = promotionSessionMapper.selectByExample(sessionExample);
        if (!CollectionUtils.isEmpty(promotionSessionList)) {
            return promotionSessionList.get(0);
        }
        return null;
    }
}
