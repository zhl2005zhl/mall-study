package com.macro.mall.portal.ai.domain;

import java.math.BigDecimal;

/**
 * 给模型看的「商品摘要」——搜索结果的瘦身版。
 *
 * 保留字段与理由同 {@link OrderSummary}：只留模型回答用户时真正用得上的。
 *
 *   id         必备：它是 getProductStock 的参数，也是「这个商品还有货吗？」的入口
 *   name       必备：用户搜索时看的就是名字
 *   subTitle   常用：型号/颜色/容量等关键信息通常在这里（如「6GB+64GB 亮黑色」），
 *              用户问「我上周买的手机」时，模型靠它判断"哪个是手机"
 *   price      必备：用户常问价格
 *   stock      必备：能直接回答「还有货吗」，省掉一次 getProductStock 调用
 *
 * 砍掉的：pic（图片 URL 对纯文本模型无意义，还白占 token）、
 *         productCategoryId / brandId 等内部 id（模型不会拿它们做判断）、
 *         promotionPrice / originalPrice 等多档价格（只给最终售价，避免模型选错）
 *
 * Created 2026/10/20.
 */
public class ProductSummary {

    /** 商品 id，可传给 getProductStock 查具体 SKU 库存。 */
    private Long id;

    /** 商品名称。 */
    private String name;

    /** 副标题，通常含规格信息。 */
    private String subTitle;

    /** 售价（元）。 */
    private BigDecimal price;

    /** 商品总库存（所有 SKU 之和），已扣除锁定库存即为可用量。 */
    private Integer stock;

    public ProductSummary() {
    }

    public ProductSummary(Long id, String name, String subTitle, BigDecimal price, Integer stock) {
        this.id = id;
        this.name = name;
        this.subTitle = subTitle;
        this.price = price;
        this.stock = stock;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubTitle() {
        return subTitle;
    }

    public void setSubTitle(String subTitle) {
        this.subTitle = subTitle;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
