package com.macro.mall.mapper;

import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.PmsSkuStockExample;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PmsSkuStockMapper {
    long countByExample(PmsSkuStockExample example);

    int deleteByExample(PmsSkuStockExample example);

    int deleteByPrimaryKey(Long id);

    int insert(PmsSkuStock row);

    int insertSelective(PmsSkuStock row);

    List<PmsSkuStock> selectByExample(PmsSkuStockExample example);

    PmsSkuStock selectByPrimaryKey(Long id);

    int updateByExampleSelective(@Param("row") PmsSkuStock row, @Param("example") PmsSkuStockExample example);

    int updateByExample(@Param("row") PmsSkuStock row, @Param("example") PmsSkuStockExample example);

    int updateByPrimaryKeySelective(PmsSkuStock row);

    int updateByPrimaryKey(PmsSkuStock row);

    /**
     * 原子锁定库存：库存充足才锁定，返回受影响行数
     * <p>
     * 用 stock - lock_stock >= quantity 把「校验」与「更新」合并为一次原子操作，
     * 消除原实现（先 select 再 update 绝对值）在并发下的丢失更新，以及检查与更新之间的时间窗问题。
     *
     * @param skuId    SKU 主键
     * @param quantity 要锁定的数量
     * @return 受影响行数，0 表示库存不足
     */
    int lockSkuStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}