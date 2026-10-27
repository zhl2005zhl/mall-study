package com.macro.mall.portal.ai.mcp;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.PmsSkuStockExample;
import com.macro.mall.portal.ai.tool.ToolResult;
import com.macro.mall.portal.service.PmsPortalProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 MCP 协议对外暴露的**商品类只读工具**。
 *
 * ============================ 为什么单独一个类，不复用 MallTools ============================
 *
 * 这是本步骤最重要的设计决策。MallTools 里有 4 个工具，其中 2 个是订单类：
 *   queryMyOrders / getOrderDetail —— 靠 SecurityContext 取当前会员来判断归属
 *
 * 而 MCP 的调用方是**外部客户端**（Claude Desktop、IDE 插件等），
 * 它不走 mall 的 JWT 登录流程，**没有 SecurityContext**。
 *
 * 所以订单类工具一旦暴露到 MCP，只有两种结局：
 *   a) 取不到用户 → 全部报错（工具形同虚设）
 *   b) 改成接受 memberId 参数 → 身份由调用方指定
 *
 * 而 (b) 正是我们之前花了大力气避免的事：**模型的输入等同于用户输入，不可信**。
 * 如果 MCP 工具签名里有 memberId，任何能访问这个端点的人都能查任意会员的订单 ——
 * 这就是把之前堵上的水平越权，从 MCP 这个新口子重新打开了。
 *
 * 结论：**订单类工具绝不通过 MCP 暴露**。MCP 只给「匿名用户也能看」的公开数据：
 * 商品信息、商品库存。这也符合电商的业务语义 —— 商品详情本来就是公开的。
 *
 * ============================ 与 MallTools 的关系 ============================
 * 不继承、不复用，而是各自独立：
 *   MallTools   → 给内部 Agent Loop 用，带身份，能查订单
 *   McpProductTools → 给外部 MCP 客户端用，无身份，只查公开商品
 * 两者共享的是**下层 Service**（PmsPortalProductService / PmsSkuStockMapper），
 * 而不是共享工具定义 —— 因为它们的权限边界本来就不同。
 *
 * Created 2026/10/27.
 */
@Component
public class McpProductTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpProductTools.class);

    /** 单次返回上限，防止一次拉太多把客户端上下文撑爆。 */
    private static final int MAX_PRODUCT_RESULT = 5;

    private final PmsPortalProductService productService;
    private final PmsProductMapper productMapper;
    private final PmsSkuStockMapper skuStockMapper;

    public McpProductTools(PmsPortalProductService productService,
                           PmsProductMapper productMapper,
                           PmsSkuStockMapper skuStockMapper) {
        this.productService = productService;
        this.productMapper = productMapper;
        this.skuStockMapper = skuStockMapper;
    }

    /**
     * 按关键词搜商品。
     *
     * 返回字段与 MallTools.searchProducts 保持一致（id/名称/副标题/价格/可用库存），
     * 因为对「搜商品」这个动作来说，需要的字段本来就是这些，没有身份差异。
     */
    @Tool(name = "searchProducts",
          description = "按关键词搜索 mall 商城的商品。返回商品 id、名称、副标题、价格、可用库存。"
                      + "这是公开的商品目录信息，不需要登录。"
                      + "拿到商品 id 后可再用 getProductStock 查具体规格的库存。")
    public ToolResult<List<ProductView>> searchProducts(
            @ToolParam(description = "搜索关键词，例如「手机」「华为」。不要传空值。", required = true)
            String keyword) {

        LOGGER.info("[MCP工具] searchProducts 被调用, keyword={}", keyword);

        if (keyword == null || keyword.trim().isEmpty()) {
            return ToolResult.error("搜索关键词不能为空。请告诉我要搜什么商品，例如「手机」。");
        }

        try {
            List<PmsProduct> products = productService.search(
                    keyword.trim(), null, null, 1, MAX_PRODUCT_RESULT, 0);

            if (products == null || products.isEmpty()) {
                return ToolResult.ok(new ArrayList<>(),
                        "没有搜到与「" + keyword.trim() + "」相关的商品。可以换个更宽泛的关键词再试。");
            }

            List<ProductView> result = new ArrayList<>();
            for (PmsProduct p : products) {
                result.add(new ProductView(
                        p.getId(), p.getName(), p.getSubTitle(),
                        p.getPrice(), calcAvailableStock(p.getId())));
            }
            return ToolResult.ok(result, "搜到 " + result.size() + " 个与「" + keyword.trim() + "」相关的商品。");
        } catch (Exception e) {
            LOGGER.error("[MCP工具] searchProducts 执行失败", e);
            return ToolResult.error("搜索商品时出现内部错误，这次没有拿到数据。"
                    + "可以换个关键词再试，或如实告诉用户稍后再试，不要编造商品信息。");
        }
    }

    /**
     * 查某商品各 SKU 的可用库存。
     *
     * 注意报的是**可用库存**（stock - lock_stock），不是总库存 ——
     * 与内部 Agent 的口径保持一致。外部调用同样要回答「用户能买到多少」。
     */
    @Tool(name = "getProductStock",
          description = "查询指定商品各规格（SKU）的可用库存与价格。"
                      + "返回的是**可用库存**（总库存减去已被订单锁定的部分），即用户真正能买到的数量。"
                      + "这是公开信息，不需要登录。")
    public ToolResult<List<SkuStockView>> getProductStock(
            @ToolParam(description = "商品 id（不是 SKU id）。可先用 searchProducts 搜商品拿到 id。", required = true)
            Long productId) {

        LOGGER.info("[MCP工具] getProductStock 被调用, productId={}", productId);

        if (productId == null) {
            return ToolResult.error("商品 id 不能为空。可先用 searchProducts 按关键词搜索拿到商品 id。");
        }
        if (productId <= 0) {
            return ToolResult.error("商品 id 必须是正整数，你传入的是 " + productId + "。");
        }

        try {
            PmsProduct product = productMapper.selectByPrimaryKey(productId);
            if (product == null || Integer.valueOf(1).equals(product.getDeleteStatus())) {
                return ToolResult.error("没有找到 id 为 " + productId + " 的商品，可能已下架或删除。"
                        + "建议用 searchProducts 按关键词搜索确认商品 id。");
            }

            PmsSkuStockExample example = new PmsSkuStockExample();
            example.createCriteria().andProductIdEqualTo(productId);
            List<PmsSkuStock> skuList = skuStockMapper.selectByExample(example);

            if (skuList == null || skuList.isEmpty()) {
                return ToolResult.ok(new ArrayList<>(),
                        "商品「" + product.getName() + "」没有配置规格，暂时无法购买。");
            }

            List<SkuStockView> result = new ArrayList<>();
            for (PmsSkuStock sku : skuList) {
                int stock = sku.getStock() == null ? 0 : sku.getStock();
                int lock = sku.getLockStock() == null ? 0 : sku.getLockStock();
                int available = Math.max(0, stock - lock);

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

            long inStock = result.stream().filter(v -> Boolean.TRUE.equals(v.getInStock())).count();
            String msg = inStock > 0
                    ? "商品「" + product.getName() + "」共 " + result.size() + " 个规格，其中 " + inStock + " 个有货。"
                    : "商品「" + product.getName() + "」全部规格已售罄。";
            return ToolResult.ok(result, msg);
        } catch (Exception e) {
            LOGGER.error("[MCP工具] getProductStock 执行失败", e);
            return ToolResult.error("查询库存时出现内部错误。可以稍后再试，或如实告诉用户这次没查到。");
        }
    }

    // ==================================================================
    // 内部辅助
    // ==================================================================

    private int calcAvailableStock(Long productId) {
        try {
            PmsSkuStockExample example = new PmsSkuStockExample();
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
            LOGGER.warn("[MCP工具] 计算商品 {} 可用库存失败", productId, e);
            return 0;
        }
    }

    private String parseSpData(String spData) {
        if (spData == null || spData.trim().isEmpty()) {
            return "";
        }
        try {
            List<String> parts = new ArrayList<>();
            Matcher m = Pattern
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

    // ==================================================================
    // 返回结构
    // ==================================================================

    /** 商品摘要（给 MCP 客户端看，字段与内部 Agent 一致）。 */
    public static class ProductView {
        private Long id;
        private String name;
        private String subTitle;
        private BigDecimal price;
        private Integer stock;

        public ProductView() {
        }

        public ProductView(Long id, String name, String subTitle, BigDecimal price, Integer stock) {
            this.id = id;
            this.name = name;
            this.subTitle = subTitle;
            this.price = price;
            this.stock = stock;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSubTitle() { return subTitle; }
        public void setSubTitle(String subTitle) { this.subTitle = subTitle; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
    }

    /** SKU 库存视图。 */
    public static class SkuStockView {
        private Long skuId;
        private String skuCode;
        private String specification;
        private BigDecimal price;
        private BigDecimal promotionPrice;
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
