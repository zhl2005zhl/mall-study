package com.macro.mall.search.dao;

import com.macro.mall.search.domain.EsProduct;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 搜索商品管理自定义Dao
 * Created by macro on 2018/6/19.
 */
public interface EsProductDao {
    /**
     * 获取指定ID的搜索商品
     */
    List<EsProduct> getAllEsProductList(@Param("id") Long id);

    /**
     * 查出「应该出现在索引里」的全部商品 id（未删除 && 已上架）。
     *
     * 定时对账用它来和 ES 里的实际 id 集合做差集：
     *   · DB 有、ES 没有 → 漏同步，补进去
     *   · ES 有、DB 没有 → 已下架/删除但没清掉，删掉
     *
     * ★ 只查 id 不查整行：对账只需要"谁应该在索引里"，
     * 而查整行会把上千个商品的所有字段读进内存，纯属浪费。
     */
    List<Long> getPublishedProductIds();
}
