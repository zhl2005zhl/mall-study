package com.macro.mall.mapper;

import com.macro.mall.model.SmsCoupon;
import com.macro.mall.model.SmsCouponExample;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SmsCouponMapper {
    long countByExample(SmsCouponExample example);

    int deleteByExample(SmsCouponExample example);

    int deleteByPrimaryKey(Long id);

    int insert(SmsCoupon row);

    int insertSelective(SmsCoupon row);

    List<SmsCoupon> selectByExample(SmsCouponExample example);

    SmsCoupon selectByPrimaryKey(Long id);

    int updateByExampleSelective(@Param("row") SmsCoupon row, @Param("example") SmsCouponExample example);

    int updateByExample(@Param("row") SmsCoupon row, @Param("example") SmsCouponExample example);

    int updateByPrimaryKeySelective(SmsCoupon row);

    int updateByPrimaryKey(SmsCoupon row);

    /**
     * 原子扣减优惠券库存（防超发）
     *
     * 「还有没有库存」和「扣减库存」在数据库里一次完成，
     * 返回 0 表示 count &lt;= 0（已领完），调用方据此判断失败。
     */
    int decreaseCount(@Param("couponId") Long couponId);
}