package com.macro.mall.mapper;

import com.macro.mall.model.SmsCouponHistory;
import com.macro.mall.model.SmsCouponHistoryExample;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SmsCouponHistoryMapper {
    long countByExample(SmsCouponHistoryExample example);

    int deleteByExample(SmsCouponHistoryExample example);

    int deleteByPrimaryKey(Long id);

    int insert(SmsCouponHistory row);

    int insertSelective(SmsCouponHistory row);

    List<SmsCouponHistory> selectByExample(SmsCouponHistoryExample example);

    SmsCouponHistory selectByPrimaryKey(Long id);

    int updateByExampleSelective(@Param("row") SmsCouponHistory row, @Param("example") SmsCouponHistoryExample example);

    int updateByExample(@Param("row") SmsCouponHistory row, @Param("example") SmsCouponHistoryExample example);

    int updateByPrimaryKeySelective(SmsCouponHistory row);

    int updateByPrimaryKey(SmsCouponHistory row);

    /**
     * 原子变更优惠券使用状态（防一券多用）
     *
     * 只有当前 use_status 等于 fromStatus 时才改成 toStatus：
     *   · 下单占用：from=0(未使用) → to=1(已使用)
     *   · 取消订单还原：from=1(已使用) → to=0(未使用)
     * 返回 0 说明状态已经被别人改过了 —— 下单占用时返回 0 就意味着
     * 这张券刚才被另一张订单抢走了，必须让整个下单事务失败。
     */
    int updateUseStatus(@Param("id") Long id,
                        @Param("fromStatus") Integer fromStatus,
                        @Param("toStatus") Integer toStatus,
                        @Param("orderId") Long orderId);
}