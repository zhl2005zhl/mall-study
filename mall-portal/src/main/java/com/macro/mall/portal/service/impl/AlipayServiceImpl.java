package com.macro.mall.portal.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.config.AlipayConfig;
import com.macro.mall.portal.domain.AliPayParam;
import com.macro.mall.portal.service.AlipayService;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.UmsMemberService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @auther macrozheng
 * @description 支付宝支付Service实现类
 * @date 2023/9/8
 * @github https://github.com/macrozheng
 */
@Slf4j
@Service
public class AlipayServiceImpl implements AlipayService {
    @Autowired
    private AlipayConfig alipayConfig;
    @Autowired
    private AlipayClient alipayClient;
    @Autowired
    private OmsOrderMapper orderMapper;
    @Autowired
    private OmsPortalOrderService portalOrderService;
    @Autowired
    private UmsMemberService memberService;
    @Override
    public String pay(AliPayParam aliPayParam) {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        if(StrUtil.isNotEmpty(alipayConfig.getNotifyUrl())){
            //异步接收地址，公网可访问
            request.setNotifyUrl(alipayConfig.getNotifyUrl());
        }
        if(StrUtil.isNotEmpty(alipayConfig.getReturnUrl())){
            //同步跳转地址
            request.setReturnUrl(alipayConfig.getReturnUrl());
        }
        //******必传参数******
        JSONObject bizContent = new JSONObject();
        //商户订单号，商家自定义，保持唯一性
        bizContent.put("out_trade_no", aliPayParam.getOutTradeNo());
        //订单总金额以数据库中订单表中为准
        // 订单总金额以数据库中订单表中为准，并且必须校验订单归属：
        // 原实现只按 orderSn 取单，任何人拿到别人的订单号就能替别人发起支付。
        OmsOrder order = getOwnedOrder(aliPayParam.getOutTradeNo());
        BigDecimal totalAmount = order.getPayAmount();
        //支付金额，最小值0.01元
        bizContent.put("total_amount", totalAmount);
        //订单标题，不可使用特殊符号
        bizContent.put("subject", aliPayParam.getSubject());
        //电脑网站支付场景固定传值FAST_INSTANT_TRADE_PAY
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        request.setBizContent(bizContent.toString());
        String formHtml = null;
        try {
            formHtml = alipayClient.pageExecute(request).getBody();
        } catch (AlipayApiException e) {
            e.printStackTrace();
        }
        return formHtml;
    }

    @Override
    public String notify(Map<String, String> params) {
        String result = "failure";
        boolean signVerified = false;
        try {
            //调用SDK验证签名
            signVerified = AlipaySignature.rsaCheckV1(params, alipayConfig.getAlipayPublicKey(), alipayConfig.getCharset(), alipayConfig.getSignType());
        } catch (AlipayApiException e) {
            log.error("支付回调签名校验异常！",e);
            e.printStackTrace();
        }
        if (signVerified) {
            String tradeStatus = params.get("trade_status");
            if("TRADE_SUCCESS".equals(tradeStatus)){
                result = "success";
                log.info("notify方法被调用了，tradeStatus:{}",tradeStatus);
                String outTradeNo = params.get("out_trade_no");
                portalOrderService.paySuccessByOrderSn(outTradeNo,1);
            }else{
                log.warn("订单未支付成功，trade_status:{}",tradeStatus);
            }
        } else {
            log.warn("支付回调签名校验失败！");
        }
        return result;
    }

    @Override
    public String query(String outTradeNo, String tradeNo) {
        // 归属校验：这个接口会顺带触发 paySuccessByOrderSn，而且它在白名单里曾经是匿名的，
        // 任何人都能拿别人的订单号来查、并顺带把别人的订单改成已支付。
        if (StrUtil.isNotEmpty(outTradeNo)) {
            checkOrderOwnership(outTradeNo);
        }
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        //******必传参数******
        JSONObject bizContent = new JSONObject();
        //设置查询参数，out_trade_no和trade_no至少传一个
        if(StrUtil.isNotEmpty(outTradeNo)){
            bizContent.put("out_trade_no",outTradeNo);
        }
        if(StrUtil.isNotEmpty(tradeNo)){
            bizContent.put("trade_no",tradeNo);
        }
        //交易结算信息: trade_settle_info
        String[] queryOptions = {"trade_settle_info"};
        bizContent.put("query_options", queryOptions);
        request.setBizContent(bizContent.toString());
        AlipayTradeQueryResponse response = null;
        try {
            response = alipayClient.execute(request);
        } catch (AlipayApiException e) {
            log.error("查询支付宝账单异常！",e);
        }
        if(response.isSuccess()){
            log.info("查询支付宝账单成功！");
            if("TRADE_SUCCESS".equals(response.getTradeStatus())){
                // 只传 tradeNo 时 outTradeNo 为空，用支付宝返回的商户订单号再校验一次归属
                String paidOrderSn = StrUtil.isNotEmpty(outTradeNo) ? outTradeNo : response.getOutTradeNo();
                checkOrderOwnership(paidOrderSn);
                portalOrderService.paySuccessByOrderSn(paidOrderSn,1);
            }
        } else {
            log.error("查询支付宝账单失败！");
        }
        //交易状态：WAIT_BUYER_PAY（交易创建，等待买家付款）、TRADE_CLOSED（未付款交易超时关闭，或支付完成后全额退款）、TRADE_SUCCESS（交易支付成功）、TRADE_FINISHED（交易结束，不可退款）
        return response.getTradeStatus();
    }

    /**
     * 取出「属于自己的」待支付订单。
     *
     * 支付相关接口最容易漏的一类校验就是归属校验：接口有登录态，但没验证
     * "这条数据是不是你的"。只按订单号取单的话，拿到别人的订单号就能替别人发起支付。
     */
    private OmsOrder getOwnedOrder(String outTradeNo) {
        OmsOrder order = portalOrderService.getOrderByOrderSn(outTradeNo);
        if (order == null || order.getPayAmount() == null) {
            Asserts.fail("订单信息或金额不能为空！");
        }
        UmsMember currentMember = memberService.getCurrentMember();
        if (!currentMember.getId().equals(order.getMemberId())) {
            // 统一说「订单不存在」，不暴露「这个订单存在、只是不属于你」，
            // 否则等于送给攻击者一个订单号探测接口
            Asserts.fail("订单不存在！");
        }
        return order;
    }

    /**
     * 校验订单归属（不过滤订单状态，用于查询 / 对账场景）。
     */
    private void checkOrderOwnership(String orderSn) {
        if (StrUtil.isEmpty(orderSn)) {
            Asserts.fail("订单不存在！");
        }
        OmsOrderExample example = new OmsOrderExample();
        example.createCriteria().andOrderSnEqualTo(orderSn).andDeleteStatusEqualTo(0);
        List<OmsOrder> orderList = orderMapper.selectByExample(example);
        if (CollUtil.isEmpty(orderList)) {
            Asserts.fail("订单不存在！");
        }
        UmsMember currentMember = memberService.getCurrentMember();
        if (!currentMember.getId().equals(orderList.get(0).getMemberId())) {
            Asserts.fail("订单不存在！");
        }
    }

    @Override
    public String webPay(AliPayParam aliPayParam) {
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest ();
        if(StrUtil.isNotEmpty(alipayConfig.getNotifyUrl())){
            //异步接收地址，公网可访问
            request.setNotifyUrl(alipayConfig.getNotifyUrl());
        }
        if(StrUtil.isNotEmpty(alipayConfig.getReturnUrl())){
            //同步跳转地址
            request.setReturnUrl(alipayConfig.getReturnUrl());
        }
        //******必传参数******
        JSONObject bizContent = new JSONObject();
        //商户订单号，商家自定义，保持唯一性
        bizContent.put("out_trade_no", aliPayParam.getOutTradeNo());
        //订单总金额以数据库中订单表中为准
        // 订单总金额以数据库中订单表中为准，并且必须校验订单归属：
        // 原实现只按 orderSn 取单，任何人拿到别人的订单号就能替别人发起支付。
        OmsOrder order = getOwnedOrder(aliPayParam.getOutTradeNo());
        BigDecimal totalAmount = order.getPayAmount();
        //支付金额，最小值0.01元
        bizContent.put("total_amount", totalAmount);
        //订单标题，不可使用特殊符号
        bizContent.put("subject", aliPayParam.getSubject());
        //手机网站支付默认传值FAST_INSTANT_TRADE_PAY
        bizContent.put("product_code", "QUICK_WAP_WAY");
        request.setBizContent(bizContent.toString());
        String formHtml = null;
        try {
            formHtml = alipayClient.pageExecute(request).getBody();
        } catch (AlipayApiException e) {
            e.printStackTrace();
        }
        return formHtml;
    }
}
