package com.macro.mall.portal.ai.domain;

import java.math.BigDecimal;

/**
 * 给模型看的「订单摘要」——**瘦身后的订单**。
 *
 * ============================ 为什么必须瘦身 ============================
 *
 * const OmsOrder 有 30+ 字段（收货人姓名/电话/省市区/详细地址、支付时间、
 * 发货时间、确认收货时间、物流公司、物流单号、各金额明细、优惠券 id、
 * 成长值、积分、备注……），加上 OmsOrderItem 后单个订单的 JSON 就上百行。
 *
 * queryMyOrders 一次返回 20 个订单 → 一次工具调用就能塞进上万 token。
 * 两三轮下来直接超出上下文窗口，而这**不是"性能问题"，是"功能不可用"**：
 * 模型会开始丢信息、答非所问，甚至报 context length exceeded。
 *
 * ============================ 保留哪 4 个字段的判断标准 ============================
 * 标准是「模型回答用户问题时**有没有用到它**」，不是「这个字段重不重要」：
 *
 *   orderSn      必备：用户要的就是订单号，而且它是后续 getOrderDetail 的参数
 *   createTime   必备：「上周买的」这类时间限定全靠它，也是排序依据
 *   statusText   必备：用户问「到哪了」，本质问的是状态；给文字不给数字
 *   totalAmount  必备：用户常问「多少钱」，也是金额类判断的依据
 *
 * 被砍掉的字段及理由（这些在 detail 工具里还能拿到，不必在列表里给）：
 *   收货人电话/地址  → 列表场景用不到，且**属于隐私数据，少喂一次就少一次泄露面**
 *   支付时间/发货时间 → 只在 "detail" 场景用
 *   物流单号        → 对「20 个订单」逐个给物流单号毫无意义，等定位到具体订单再查
 *   各优惠金额明细   → 用户只关心最终付了多少
 *
 * ============================ 状态为什么给文字 ============================
 * 库里 status 是 0-5 的数字。如果直接把 0 喂给模型，它要么猜错，
 * 要么在回答里说「您的订单状态是 0」——对用户毫无意义。
 * 转换成人话（"待付款"）放在工具层做，比让模型自己记映射表更可靠：
 * 映射表一旦在 prompt 里漏了，模型就会编。
 *
 * Created 2026/10/20.
 */
public class OrderSummary {

    /** 订单号。后续查详情 / 查物流都靠它。 */
    private String orderSn;

    /** 下单时间，格式 yyyy-MM-dd HH:mm。模型用它回答「上周/昨天买的」。 */
    private String createTime;

    /** 订单状态的中文描述，如「待付款」「已发货」。 */
    private String statusText;

    /** 订单实际支付金额（元）。 */
    private BigDecimal totalAmount;

    public OrderSummary() {
    }

    public OrderSummary(String orderSn, String createTime, String statusText, BigDecimal totalAmount) {
        this.orderSn = orderSn;
        this.createTime = createTime;
        this.statusText = statusText;
        this.totalAmount = totalAmount;
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

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
}
