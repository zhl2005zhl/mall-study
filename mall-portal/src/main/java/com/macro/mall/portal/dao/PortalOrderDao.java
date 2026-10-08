package com.macro.mall.portal.dao;

import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.portal.domain.OmsOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 前台订单管理自定义Dao
 * Created by macro on 2018/9/4.
 */
public interface PortalOrderDao {
    /**
     * 获取订单及下单商品详情
     */
    OmsOrderDetail getDetail(@Param("orderId") Long orderId);

    /**
     * 修改 pms_sku_stock表的锁定库存及真实库存
     */
    int updateSkuStock(@Param("itemList") List<OmsOrderItem> orderItemList);

    /**
     * 获取超时订单
     *
     * @param minute 超时时间（分）
     * @param limit  本次最多取多少个订单
     *               <p>
     *               为什么要加 limit：原实现一次性把「所有」超时订单连明细捞出来，
     *               大促后积压几十万条时（本项目实测 33 万）会直接打爆数据库报文上限和 JVM 堆。
     *               改成每次只取一批、循环处理，才能做到「失败可续跑、内存可控」。
     */
    List<OmsOrderDetail> getTimeOutOrders(@Param("minute") Integer minute, @Param("limit") Integer limit);

    /**
     * 批量修改订单状态
     */
    int updateOrderStatus(@Param("ids") List<Long> ids,@Param("status") Integer status);

    /**
     * 解除取消订单的库存锁定
     */
    int releaseSkuStockLock(@Param("itemList") List<OmsOrderItem> orderItemList);

}
