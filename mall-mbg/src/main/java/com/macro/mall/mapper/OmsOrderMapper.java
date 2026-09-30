package com.macro.mall.mapper;

import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OmsOrderMapper {
    long countByExample(OmsOrderExample example);

    int deleteByExample(OmsOrderExample example);

    int deleteByPrimaryKey(Long id);

    int insert(OmsOrder row);

    int insertSelective(OmsOrder row);

    List<OmsOrder> selectByExample(OmsOrderExample example);

    OmsOrder selectByPrimaryKey(Long id);

    int updateByExampleSelective(@Param("row") OmsOrder row, @Param("example") OmsOrderExample example);

    int updateByExample(@Param("row") OmsOrder row, @Param("example") OmsOrderExample example);

    int updateByPrimaryKeySelective(OmsOrder row);

    int updateByPrimaryKey(OmsOrder row);

    /**
     * 把订单从「待支付」原子置为「已支付」（支付回调的幂等键）
     *
     * 只有当前 status = 0 时才更新。返回 0 说明这个订单已经被处理过了
     * （重复回调 / 已取消 / 不存在），调用方应直接返回，不要再去扣库存。
     */
    int updateOrderStatusToPaid(@Param("orderId") Long orderId, @Param("payType") Integer payType);
}