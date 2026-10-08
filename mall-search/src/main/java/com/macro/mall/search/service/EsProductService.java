package com.macro.mall.search.service;

import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 搜索商品管理Service
 * Created by macro on 2018/6/19.
 */
public interface EsProductService {
    /**
     * 从数据库中导入所有商品到ES
     */
    int importAll();

    /**
     * 把单个商品的索引状态「收敛」到与数据库一致。
     *
     * 这是自动同步链路的核心方法：它**不是**"执行某个操作"，而是
     * "让索引和数据库对齐"。内部会回查数据库判断该商品现在应不应该出现在索引里：
     *   · 应该（未删除 && 已上架）→ upsert
     *   · 不应该（已删除 / 已下架）→ 从索引删除
     *
     * ★ 后半句是关键。原来的 create(id) 只会做 upsert，
     * 下架商品时 getAllEsProductList 返回空列表、于是"什么都不做"，
     * 索引里的旧文档就永远留着了 —— 这才是"下架了还能搜到"的真正原因。
     */
    void syncProduct(Long id);

    /**
     * 批量收敛。定时对账与消息消费都走这个入口。
     *
     * @return 实际发生变更（新增/更新/删除）的商品数
     */
    int syncProducts(List<Long> ids);

    /**
     * 根据id删除商品
     */
    void delete(Long id);

    /**
     * 根据id创建商品
     */
    EsProduct create(Long id);

    /**
     * 批量删除商品
     */
    void delete(List<Long> ids);

    /**
     * 根据关键字通过名称或副标题查询商品
     */
    Page<EsProduct> search(String keyword, Integer pageNum, Integer pageSize);

    /**
     * 根据关键字通过名称或副标题复合查询商品
     */
    Page<EsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize,Integer sort);

    /**
     * 根据商品id推荐相关商品
     */
    Page<EsProduct> recommend(Long id, Integer pageNum, Integer pageSize);

    /**
     * 搜索关键字相关品牌、分类、属性
     */
    EsProductRelatedInfo searchRelatedInfo(String keyword);
}
