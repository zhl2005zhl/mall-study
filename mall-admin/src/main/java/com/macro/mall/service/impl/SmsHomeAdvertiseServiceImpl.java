package com.macro.mall.service.impl;

import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.constant.CacheKeys;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.SmsHomeAdvertiseMapper;
import com.macro.mall.model.SmsHomeAdvertise;
import com.macro.mall.model.SmsHomeAdvertiseExample;
import com.macro.mall.service.SmsHomeAdvertiseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 首页广告管理Service实现类
 * Created by macro on 2018/11/7.
 */
@Service
public class SmsHomeAdvertiseServiceImpl implements SmsHomeAdvertiseService {
    @Autowired
    private SmsHomeAdvertiseMapper advertiseMapper;
    @Autowired
    private RedisService redisService;

    @Override
    public int create(SmsHomeAdvertise advertise) {
        advertise.setClickCount(0);
        advertise.setOrderCount(0);
        int count = advertiseMapper.insert(advertise);
        evictHomeContentCache();
        return count;
    }

    @Override
    public int delete(List<Long> ids) {
        SmsHomeAdvertiseExample example = new SmsHomeAdvertiseExample();
        example.createCriteria().andIdIn(ids);
        int count = advertiseMapper.deleteByExample(example);
        evictHomeContentCache();
        return count;
    }

    @Override
    public int updateStatus(Long id, Integer status) {
        SmsHomeAdvertise record = new SmsHomeAdvertise();
        record.setId(id);
        record.setStatus(status);
        int count = advertiseMapper.updateByPrimaryKeySelective(record);
        evictHomeContentCache();
        return count;
    }

    @Override
    public SmsHomeAdvertise getItem(Long id) {
        return advertiseMapper.selectByPrimaryKey(id);
    }

    @Override
    public int update(Long id, SmsHomeAdvertise advertise) {
        advertise.setId(id);
        int count = advertiseMapper.updateByPrimaryKeySelective(advertise);
        evictHomeContentCache();
        return count;
    }

    /**
     * 后台改了广告位就清掉首页缓存。
     *
     * 为什么是「删缓存」而不是「改缓存」：写缓存要多维护一份组装逻辑，
     * 还容易和 portal 侧的写入打架；直接删掉让下次请求回源重建，最不容易出错。
     *
     * 同样的做法要套用到其它首页素材上（推荐品牌 / 新品 / 人气 / 专题 / 秒杀），
     * 这里以广告位为代表。
     */
    private void evictHomeContentCache() {
        redisService.del(CacheKeys.HOME_CONTENT);
    }

    @Override
    public List<SmsHomeAdvertise> list(String name, Integer type, String endTime, Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        SmsHomeAdvertiseExample example = new SmsHomeAdvertiseExample();
        SmsHomeAdvertiseExample.Criteria criteria = example.createCriteria();
        if (!StrUtil.isEmpty(name)) {
            criteria.andNameLike("%" + name + "%");
        }
        if (type != null) {
            criteria.andTypeEqualTo(type);
        }
        if (!StrUtil.isEmpty(endTime)) {
            String startStr = endTime + " 00:00:00";
            String endStr = endTime + " 23:59:59";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date start = null;
            try {
                start = sdf.parse(startStr);
            } catch (ParseException e) {
                e.printStackTrace();
            }
            Date end = null;
            try {
                end = sdf.parse(endStr);
            } catch (ParseException e) {
                e.printStackTrace();
            }
            if (start != null && end != null) {
                criteria.andEndTimeBetween(start, end);
            }
        }
        example.setOrderByClause("sort desc");
        return advertiseMapper.selectByExample(example);
    }
}
