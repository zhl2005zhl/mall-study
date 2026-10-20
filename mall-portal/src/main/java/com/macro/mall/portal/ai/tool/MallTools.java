package com.macro.mall.portal.ai.tool;

import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.ai.domain.OrderDetailView;
import com.macro.mall.portal.ai.domain.OrderSummary;
import com.macro.mall.portal.ai.domain.ProductSummary;
import com.macro.mall.portal.service.OmsPortalOrderService;
import com.macro.mall.portal.service.PmsPortalProductService;
import com.macro.mall.portal.service.UmsMemberService;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.common.api.CommonPage;
import com.macro.mall.portal.domain.OmsOrderDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * mall 的 AI 只读工具集：把已有业务 Service 包装成模型可调用的「能力」。
 *
 * ============================ 这一层的定位 ============================
 * 复用已有 Service，**不新写任何业务逻辑**。这里只做三件事：
 *   1. 参数校验（模型一定会传垃圾参数）
 *   2. 结果瘦身（只回填模型推理需要的字段）
 *   3. 错误转可读信息（让模型能自愈，而不是让整个流程崩掉）
 *
 * ============================ 安全设计（最重要的一条） ============================
 *
 * ★ 所有工具的参数里**没有 memberId**，身份一律从 SecurityContext 取。
 *
 *   为什么：模型的输入等同于用户输入，同样不可信。
 *   如果工具签名是 getOrders(Long memberId, ...)，模型只要输出 memberId=2，
 *   就能查别人的订单 —— 这是一种**水平越权**，而且非常隐蔽：
 *   代码看起来完全正常，鉴权在 Controller 层也做了，唯独工具这一层绕过了。
 *
 *   核心认知：**凡是「模型能传的身份」，就等于「用户可以伪造的身份」。**
 *
 * ============================ 粒度为什么是「业务动作」级 ============================
 *   太细（每个字段一个工具）→ 工具清单几十个，模型选择困难，还会组合出错误调用
 *   太粗（给一个 executeSql(sql)）→ 等于把数据库交给模型，既无法鉴权也无法审计
 *
 *   判断标准：**这个工具名能不能直接读给用户听**。
 *   「查我的订单」「查商品库存」「搜商品」都能读给用户听 —— 粒度合适。
 *
 * ============================ 为什么全部是只读 ============================
 * 读操作错了没损失，可以放心自动执行。
 * 写操作（退款、改地址、取消订单）必须走确认流程，不能有「模型说退款就退款」的设计 ——
 * 那就不是技术问题了，是事故。所以这一批先从只读做起。
 *
 * Created 2026/10/20.
 */
@Component
public class MallTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(MallTools.class);

    /** 单次返回给模型的订单上限。防止一次塞进过多数据导致上下文爆炸。 */
    private static final int MAX_ORDER_RESULT = 10;

    /** 单次返回给模型的商品上限。 */
    private static final int MAX_PRODUCT_RESULT = 5;

    /** 订单号规则：8 位日期 + 2 位来源 + 2 位支付 + 6 位序列 = 18 位纯数字。 */
    private static final int ORDER_SN_LENGTH = 18;

    private final OmsPortalOrderService orderService;
    private final PmsPortalProductService productService;
    private final UmsMemberService memberService;
    private final PmsSkuStockMapper skuStockMapper;
    private final PmsProductMapper productMapper;

    public MallTools(OmsPortalOrderService orderService,
                     PmsPortalProductService productService,
                     UmsMemberService memberService,
                     PmsSkuStockMapper skuStockMapper,
                     PmsProductMapper productMapper) {
        this.orderService = orderService;
        this.productService = productService;
        this.memberService = memberService;
        this.skuStockMapper = skuStockMapper;
        this.productMapper = productMapper;
    }

    // ==================================================================
    // 工具 1：查我的订单
    // ==================================================================

    /**
     * 查当前登录会员的订单列表。
     *
     * 【为什么没有 memberId 参数】见类注释的安全设计。身份从 SecurityContext 取。
     *
     * 【为什么返回 List 而不是完整订单对象】见 OrderSummary 的注释：瘦身。
     *
     * 【异常为什么在这里兜住】工具一旦抛异常，Spring AI 会包成 ToolExecutionException，
     * 模型看到的是堆栈摘要而不是人话，无法据此修正。所以统一转成 ToolResult.error。
     */
    @Tool(name = "queryMyOrders",
          description = "查询当前登录用户自己的订单列表。"
                      + "返回订单号、下单时间、状态、实付金额。"
                      + "当用户问「我的订单」「我买了什么」「上周买的手机」等问题时使用。"
                      + "只能查到当前用户自己的订单，无法查询他人订单。")
    public ToolResult<List<OrderSummary>> queryMyOrders(
            @ToolParam(description = "订单状态筛选，可选。0=待付款 1=待发货 2=已发货 3=已完成 4=已关闭。不传则返回全部状态。", required = false)
            Integer status) {

        LOGGER.info("[AI工具] queryMyOrders 被调用, status={}", status);
        try {
            // 身份从会话取 —— 参数里根本没有 memberId，模型无法伪造
            UmsMember member = memberService.getCurrentMember();
            if (member == null) {
                return ToolResult.error("当前未登录，无法查询订单。请先登录。");
            }

            // 复用已有 Service 分页查询。pageSize 用固定值而非让模型传，
            // 避免模型传 size=1000 把上下文撑爆。
            CommonPage<OmsOrderDetail> page = orderService.list(status, 1, MAX_ORDER_RESULT);
            List<OmsOrderDetail> orderList = page == null ? null : page.getList();

            if (orderList == null || orderList.isEmpty()) {
                String when = status == null ? "" : "在状态「" + statusText(status) + "」下";
                return ToolResult.ok(new ArrayList<>(),
                        "你" + when + "没有任何订单。");
            }

            // ★ 归属二次校验：Service 层已按当前会员过滤，这里再确认一遍。
            //   防御的是「将来有人改了 Service 的过滤条件」——安全校验不应该只靠一层。
            List<OrderSummary> result = orderList.stream()
                    .filter(o -> member.getId().equals(o.getMemberId()))
                    .map(this::toSummary)
                    .collect(Collectors.toList());

            if (result.isEmpty()) {
                return ToolResult.ok(new ArrayList<>(), "你没有任何符合条件的订单。");
            }
            return ToolResult.ok(result,
                    "查到 " + result.size() + " 个订单。");

        } catch (Exception e) {
            // 兜住所有异常，转成模型能理解的错误。
            // 注意：这里不打印完整堆栈给模型（对模型无用），但服务端日志要留全。
            return handleToolError("queryMyOrders", e);
        }
    }

    // ==================================================================
    // 工具 2：查订单详情
    // ==================================================================

    /**
     * 按订单号查订单详情。
     *
     * 【这里藏着最关键的安全点】按订单号查单，任何人都能拿到别人的订单号 ——
     * 所以必须校验「这个订单是不是当前会员的」。原项目的
     * OmsPortalOrderServiceImpl.detail(Long orderId) 是**按 id 直接查、不校验归属**的，
     * 它之所以没暴露问题，是因为 Controller 层有额外的会员过滤。
     * 工具层不能依赖那个 Controller，必须自己校验。
     */
    @Tool(name = "getOrderDetail",
          description = "根据订单号查询订单详情，包含买了什么、当前状态、物流信息、收货信息。"
                      + "当用户已经指明某个具体订单（如「2026102001...这个订单到哪了」）时使用。"
                      + "只能查当前用户自己的订单。")
    public ToolResult<OrderDetailView> getOrderDetail(
            @ToolParam(description = "订单号，18 位纯数字，例如 202610200101000001。", required = true)
            String orderSn) {

        LOGGER.info("[AI工具] getOrderDetail 被调用, orderSn={}", orderSn);

        // ---- 参数校验：错误信息要写成模型能据此改对的样子 ----
        if (orderSn == null || orderSn.trim().isEmpty()) {
            return ToolResult.error(
                    "订单号不能为空。如果你不知道订单号，可以先用 queryMyOrders 列出你的全部订单，"
                    + "从里面找到目标订单的订单号。");
        }
        String sn = orderSn.trim();
        if (!sn.matches("\\d+")) {
            return ToolResult.error(
                    "订单号格式错误：应为 " + ORDER_SN_LENGTH + " 位纯数字，"
                    + "你传入的是 \"" + sn + "\"。"
                    + "如果这是手机号或其他信息，说明你拿错了字段 —— "
                    + "可以先用 queryMyOrders 列出全部订单，再从结果里取订单号。");
        }
        if (sn.length() != ORDER_SN_LENGTH) {
            return ToolResult.error(
                    "订单号长度不对：应为 " + ORDER_SN_LENGTH + " 位纯数字，"
                    + "你传入的是 " + sn.length() + " 位（\"" + sn + "\"）。"
                    + "建议先用 queryMyOrders 列出全部订单，直接复制其中的订单号。");
        }

        try {
            UmsMember member = memberService.getCurrentMember();
            if (member == null) {
                return ToolResult.error("当前未登录，无法查询订单。请先登录。");
            }

            OmsOrder order = orderService.getOrderByOrderSn(sn);
            if (order == null) {
                return ToolResult.error(
                        "没有找到订单号为 " + sn + " 的订单。"
                        + "可能原因：订单号输入有误，或该订单不属于你。"
                        + "建议改用 queryMyOrders 列出你的全部订单核对。");
            }

            // ★ 归属校验：这个订单必须是当前会员的。
            //   不通过时**不要**说"这是别人的订单" —— 那等于泄露了该订单号确实存在，
            //   属于可被枚举利用的信息泄露。统一按"找不到"处理。
            if (!member.getId().equals(order.getMemberId())) {
                LOGGER.warn("[AI工具] 越权尝试被拦截: memberId={} 试图查询 orderSn={}",
                        member.getId(), sn);
                return ToolResult.error(
                        "没有找到订单号为 " + sn + " 的订单。"
                        + "（该订单不在你名下）请用 queryMyOrders 查看你自己的订单。");
            }

            OmsOrderDetail detail = orderService.detail(order.getId());
            return ToolResult.ok(toDetailView(order, detail), "已查到订单 " + sn + " 的详情。");

        } catch (Exception e) {
            return handleToolError("getOrderDetail", e);
        }
    }

    // ==================================================================
    // 工具 3：查商品库存
    // ==================================================================

    /**
     * 查某商品各 SKU 的可用库存。
     *
     * 【为什么用「可用库存」而不是 stock】项目里的库存是三段式：
     *   stock       总库存
     *   lock_stock  已被下单锁定、尚未支付/发货的数量
     *   可用 = stock - lock_stock
     * 只报 stock 会让模型回答「还有 484 件」，而实际可能已经被锁定得只剩个位数。
     * 这正是项目里那个并发超卖缺陷的同一套概念 —— 回答用户要答「可用」，不是「总数」。
     */
    @Tool(name = "getProductStock",
          description = "查询指定商品的各规格（SKU）可用库存、价格。"
                      + "当用户问「这个商品还有货吗」「还有多少件」时使用。"
                      + "返回的是**可用库存**（总库存减去已被订单锁定的部分），是用户真正能买到的数量。")
    public ToolResult<List<SkuStockView>> getProductStock(
            @ToolParam(description = "商品 id（注意不是 SKU id）。可先用 searchProducts 搜商品拿到 id。", required = true)
            Long productId) {

        LOGGER.info("[AI工具] getProductStock 被调用, productId={}", productId);

        if (productId == null) {
            return ToolResult.error(
                    "商品 id 不能为空。可以先用 searchProducts 按关键词搜索商品，"
                    + "从结果里拿到商品的 id（不是 SKU id）。");
        }
        if (productId <= 0) {
            return ToolResult.error("商品 id 必须是正整数，你传入的是 " + productId + "。");
        }

        try {
            PmsProduct product = productMapper.selectByPrimaryKey(productId);
            if (product == null || Integer.valueOf(1).equals(product.getDeleteStatus())) {
                return ToolResult.error(
                        "没有找到 id 为 " + productId + " 的商品。"
                        + "可能已下架或删除。建议用 searchProducts 按关键词搜索确认商品 id。");
            }

            com.macro.mall.model.PmsSkuStockExample example =
                    new com.macro.mall.model.PmsSkuStockExample();
            example.createCriteria().andProductIdEqualTo(productId);
            List<PmsSkuStock> skuList = skuStockMapper.selectByExample(example);

            if (skuList == null || skuList.isEmpty()) {
                return ToolResult.ok(new ArrayList<>(),
                        "商品「" + product.getName() + "」没有配置任何规格，暂时无法购买。");
            }

            List<SkuStockView> result = new ArrayList<>();
            for (PmsSkuStock sku : skuList) {
                int stock = sku.getStock() == null ? 0 : sku.getStock();
                int lockStock = sku.getLockStock() == null ? 0 : sku.getLockStock();
                // 可用库存 —— 负数按 0 处理（历史脏数据可能让 lock_stock 超卖成负数）
                int available = Math.max(0, stock - lockStock);

                SkuStockView v = new SkuStockView();
                v.setSkuId(sku.getId());
                v.setSkuCode(sku.getSkuCode());
                v.setSpecification(parseSpData(sku.getSpData()));
                v.setPrice(sku.getPrice());
                v.setPromotionPrice(sku.getPromotionPrice());
                v.setAvailableStock(available);
                v.setInStock(available > 0);
                result.add(v);
            }

            long inStockCount = result.stream().filter(v -> Boolean.TRUE.equals(v.getInStock())).count();
            String msg = inStockCount > 0
                    ? "商品「" + product.getName() + "」共 " + result.size() + " 个规格，其中 "
                      + inStockCount + " 个有货。"
                    : "商品「" + product.getName() + "」全部规格都已售罄。";
            return ToolResult.ok(result, msg);

        } catch (Exception e) {
            return handleToolError("getProductStock", e);
        }
    }

    // ==================================================================
    // 工具 4（附赠）：搜商品
    // ==================================================================

    /**
     * 按关键词搜商品。
     *
     * 【为什么这个必须排在最后才写进 loop】它是「信息不全时」的兜底工具：
     * 用户说「我买的手机」，模型不知道商品 id，得先搜。
     * 没有它，模型只能问用户要 id —— 体验立刻掉档。
     */
    @Tool(name = "searchProducts",
          description = "按关键词搜索商品。返回商品 id、名称、副标题、价格、库存。"
                      + "当用户提到某个商品但没给商品 id 时使用（如「华为手机还有货吗」）。"
                      + "拿到商品 id 后，再用 getProductStock 查询具体规格的库存。")
    public ToolResult<List<ProductSummary>> searchProducts(
            @ToolParam(description = "搜索关键词，例如「手机」「华为」。不要传空值。", required = true)
            String keyword) {

        LOGGER.info("[AI工具] searchProducts 被调用, keyword={}", keyword);

        if (keyword == null || keyword.trim().isEmpty()) {
            return ToolResult.error(
                    "搜索关键词不能为空。请告诉我要搜什么商品，例如「手机」「华为」。");
        }

        try {
            // 复用已有 Service。pageSize 固定上限，避免模型无意中拉太多。
            List<PmsProduct> products = productService.search(
                    keyword.trim(), null, null, 1, MAX_PRODUCT_RESULT, 0);

            if (products == null || products.isEmpty()) {
                return ToolResult.ok(new ArrayList<>(),
                        "没有搜到与「" + keyword.trim() + "」相关的商品。"
                        + "可以换个更宽泛的关键词再试，例如把「华为 P20 金色 16G」改成「手机」。");
            }

            List<ProductSummary> result = new ArrayList<>();
            for (PmsProduct p : products) {
                // 总可用库存：该商品所有 SKU 的 (stock - lock_stock) 之和
                int totalAvailable = calcProductAvailableStock(p.getId());
                result.add(new ProductSummary(
                        p.getId(), p.getName(), p.getSubTitle(), p.getPrice(), totalAvailable));
            }
            return ToolResult.ok(result,
                    "搜到 " + result.size() + " 个与「" + keyword.trim() + "」相关的商品。");

        } catch (Exception e) {
            return handleToolError("searchProducts", e);
        }
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    /**
     * 异常统一处理：转成模型能读懂的错误，同时把完整堆栈留在服务端日志。
     *
     * 这是「错误回填而非抛异常」原则的落地点。返回的 message 里刻意不包含
     * 异常类名和堆栈 —— 那些对模型没有帮助，只会污染上下文。
     */
    private <T> ToolResult<T> handleToolError(String toolName, Exception e) {
        LOGGER.error("[AI工具] {} 执行失败", toolName, e);
        String reason = e.getMessage() == null ? "" : e.getMessage();
        // 业务校验异常（Asserts.fail）的 message 本来就是说人话的，可以直接给模型
        if (reason.contains("库存不足") || reason.contains("不存在")) {
            return ToolResult.<T>error("操作未完成：" + reason);
        }
        return ToolResult.<T>error(
                "工具 " + toolName + " 执行时出现内部错误，这次调用没有拿到数据。"
                + "可以换个方式再试（例如改用 queryMyOrders 列出订单），"
                + "或者如实告诉用户稍后再试，不要编造数据。");
    }

    /** 订单状态码转中文。给模型文字而不是数字，避免它自己编映射。 */
    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 0: return "待付款";
            case 1: return "待发货";
            case 2: return "已发货";
            case 3: return "已完成";
            case 4: return "已关闭";
            case 5: return "无效订单";
            default: return "未知(" + status + ")";
        }
    }

    private OrderSummary toSummary(OmsOrderDetail o) {
        return new OrderSummary(
                o.getOrderSn(),
                formatDate(o.getCreateTime()),
                statusText(o.getStatus()),
                o.getPayAmount() != null ? o.getPayAmount() : o.getTotalAmount());
    }

    private OrderDetailView toDetailView(OmsOrder order, OmsOrderDetail detail) {
        OrderDetailView v = new OrderDetailView();
        v.setOrderSn(order.getOrderSn());
        v.setCreateTime(formatDate(order.getCreateTime()));
        v.setStatusText(statusText(order.getStatus()));
        v.setTotalAmount(money(order.getPayAmount() != null ? order.getPayAmount() : order.getTotalAmount()));
        v.setPaymentTime(order.getPaymentTime() == null ? "未支付" : formatDate(order.getPaymentTime()));

        // 商品：名称 × 数量，多个用「、」连接
        if (detail != null && detail.getOrderItemList() != null && !detail.getOrderItemList().isEmpty()) {
            String names = detail.getOrderItemList().stream()
                    .map(i -> safe(i.getProductName()) + " ×" + i.getProductQuantity())
                    .collect(Collectors.joining("、"));
            v.setProductNames(names);
        } else {
            v.setProductNames("（无商品明细）");
        }

        // 物流
        if (order.getDeliveryTime() == null) {
            v.setDeliveryInfo("未发货");
        } else {
            String company = safe(order.getDeliveryCompany());
            String sn = safe(order.getDeliverySn());
            v.setDeliveryInfo(("".equals(company) ? "物流公司未知" : company)
                    + " 单号 " + ("".equals(sn) ? "未知" : sn)
                    + "，发货时间 " + formatDate(order.getDeliveryTime()));
        }

        // 收货信息 —— 只给省市区+详细地址，不给收货人电话
        v.setReceiveInfo(safe(order.getReceiverProvince()) + " "
                + safe(order.getReceiverCity()) + " "
                + safe(order.getReceiverRegion()) + " "
                + safe(order.getReceiverDetailAddress()));

        return v;
    }

    /** 计算商品的可用库存总和：Σ (stock - lock_stock)。 */
    private int calcProductAvailableStock(Long productId) {
        try {
            com.macro.mall.model.PmsSkuStockExample example =
                    new com.macro.mall.model.PmsSkuStockExample();
            example.createCriteria().andProductIdEqualTo(productId);
            List<PmsSkuStock> list = skuStockMapper.selectByExample(example);
            int total = 0;
            for (PmsSkuStock s : list) {
                int stock = s.getStock() == null ? 0 : s.getStock();
                int lock = s.getLockStock() == null ? 0 : s.getLockStock();
                total += Math.max(0, stock - lock);
            }
            return total;
        } catch (Exception e) {
            LOGGER.warn("[AI工具] 计算商品 {} 可用库存失败", productId, e);
            return 0;
        }
    }

    /**
     * 把 SKU 的 spData（JSON 数组，形如 [{"key":"颜色","value":"金色"}]）
     * 转成人类可读的「颜色:金色, 容量:16G」。
     *
     * 模型能直接读懂这种格式；原始 JSON 也能读，但会多占不少 token。
     */
    private String parseSpData(String spData) {
        if (spData == null || spData.trim().isEmpty()) {
            return "";
        }
        try {
            List<String> parts = new ArrayList<>();
            // 轻量解析：不引入额外依赖，按 "key":"value" 成对提取
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"key\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(spData);
            while (m.find()) {
                parts.add(m.group(1) + ":" + m.group(2));
            }
            return String.join(", ", parts);
        } catch (Exception e) {
            return "";
        }
    }

    private String formatDate(java.util.Date date) {
        if (date == null) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(date);
    }

    private String money(BigDecimal d) {
        return d == null ? "0.00" : d.toPlainString();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * SKU 库存视图 —— 给模型看的瘦身结果。
     */
    public static class SkuStockView {
        private Long skuId;
        private String skuCode;
        /** 规格描述，如「颜色:金色, 容量:16G」。 */
        private String specification;
        private BigDecimal price;
        private BigDecimal promotionPrice;
        /** 可用库存 = stock - lock_stock。 */
        private Integer availableStock;
        private Boolean inStock;

        public Long getSkuId() { return skuId; }
        public void setSkuId(Long skuId) { this.skuId = skuId; }
        public String getSkuCode() { return skuCode; }
        public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
        public String getSpecification() { return specification; }
        public void setSpecification(String specification) { this.specification = specification; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public BigDecimal getPromotionPrice() { return promotionPrice; }
        public void setPromotionPrice(BigDecimal promotionPrice) { this.promotionPrice = promotionPrice; }
        public Integer getAvailableStock() { return availableStock; }
        public void setAvailableStock(Integer availableStock) { this.availableStock = availableStock; }
        public Boolean getInStock() { return inStock; }
        public void setInStock(Boolean inStock) { this.inStock = inStock; }
    }
}
