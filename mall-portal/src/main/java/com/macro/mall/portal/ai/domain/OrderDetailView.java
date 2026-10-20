package com.macro.mall.portal.ai.domain;

/**
 * 给模型看的「订单详情」——比 {@link OrderSummary} 多一层，仍然瘦身。
 *
 * 什么时候需要它：用户已经锁定某个订单（「我上周买的**那台手机**到哪了」），
 * 需要知道买了什么、什么状态、什么时候能到。
 *
 * 相比 OrderSummary 多给的字段：
 *   productNames   买了什么 —— 模型用它确认「这是不是用户说的那台手机」
 *   deliveryInfo   发货/物流 —— 回答「到哪了」的核心
 *   receiveInfo    收货信息 —— 回答「寄到哪了」
 *
 * 故意不给的：
 *   收货人**电话**    对回答问题无帮助，且是隐私数据
 *   各金额明细       用户只关心实付，明细会诱导模型算错
 *   内部优惠券 id    模型不认识这些 id
 *
 * 【安全边界】memberId 不在这里出现。工具内部用它做归属校验，
 * 但绝不返回给模型 —— 模型知道的会员信息越少越好。
 *
 * Created 2026/10/20.
 */
public class OrderDetailView {

    private String orderSn;

    private String createTime;

    private String statusText;

    /** 买了哪些商品，形如「华为 HUAWEI P20 ×3」。 */
    private String productNames;

    /** 实付金额（元）。 */
    private String totalAmount;

    /** 支付时间，未支付为「未支付」。 */
    private String paymentTime;

    /** 发货信息，形如「顺丰速运 单号 123456」。未发货为「未发货」。 */
    private String deliveryInfo;

    /** 收货信息，形如「广东省 深圳市 南山区 xxx」。 */
    private String receiveInfo;

    public OrderDetailView() {
    }

    public String getOrderSn() {
        return orderSn;
    }

    public void setOrderSn(String orderSn) {
        this.orderSn = orderSn;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    public String getProductNames() {
        return productNames;
    }

    public void setProductNames(String productNames) {
        this.productNames = productNames;
    }

    public String getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(String totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getPaymentTime() {
        return paymentTime;
    }

    public void setPaymentTime(String paymentTime) {
        this.paymentTime = paymentTime;
    }

    public String getDeliveryInfo() {
        return deliveryInfo;
    }

    public void setDeliveryInfo(String deliveryInfo) {
        this.deliveryInfo = deliveryInfo;
    }

    public String getReceiveInfo() {
        return receiveInfo;
    }

    public void setReceiveInfo(String receiveInfo) {
        this.receiveInfo = receiveInfo;
    }
}
