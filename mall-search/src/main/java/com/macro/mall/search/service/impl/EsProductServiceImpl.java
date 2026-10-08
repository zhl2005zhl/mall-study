package com.macro.mall.search.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.aggregations.*;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.util.ObjectBuilder;
import com.macro.mall.search.dao.EsProductDao;
import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductRelatedInfo;
import com.macro.mall.search.repository.EsProductRepository;
import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.elasticsearch.client.elc.*;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 搜索商品管理Service实现类
 * Created by macro on 2018/6/19.
 */
@Service
public class EsProductServiceImpl implements EsProductService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EsProductServiceImpl.class);
    @Autowired
    private EsProductDao productDao;
    @Autowired
    private EsProductRepository productRepository;
    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;
    @Override
    public int importAll() {
        List<EsProduct> esProductList = productDao.getAllEsProductList(null);
        Iterable<EsProduct> esProductIterable = productRepository.saveAll(esProductList);
        Iterator<EsProduct> iterator = esProductIterable.iterator();
        int result = 0;
        while (iterator.hasNext()) {
            result++;
            iterator.next();
        }
        return result;
    }

    @Override
    public void delete(Long id) {
        productRepository.deleteById(id);
    }

    /**
     * 把单个商品的索引状态收敛到与数据库一致。
     *
     * 【为什么判断条件要从"够不够上榜"来推，而不是从"操作是什么"来推】
     * 消息里不携带操作类型（详见 ProductSyncMessage 的注释），
     * 所以这里统一问一个问题：getAllEsProductList(id) 有没有返回数据？
     * 那条 SQL 的条件是 `delete_status = 0 and publish_status = 1`，
     * 也就是说 —— **它返回的行 = 这个商品应该出现在索引里的唯一判据**。
     *
     * 这样"该不该在索引里"的规则只有一处定义（那条 SQL），
     * 不会出现"上架路径写了、下架路径忘了"的不一致。
     */
    @Override
    public void syncProduct(Long id) {
        if (id == null) {
            return;
        }
        List<EsProduct> list = productDao.getAllEsProductList(id);
        if (CollectionUtils.isEmpty(list)) {
            // 已删除 / 已下架 / 不存在 → 必须从索引里移除。
            // 这一句就是台账 #9「下架商品仍能被搜到」的修复点。
            //
            // ★ 为什么要先 existsById 再删，而不是直接 deleteById：
            //   Spring Data ES 的 deleteById 在文档不存在时会抛 DocumentMissingException。
            //   而"下架一个从没导入过索引的商品"是完全正常的（新建商品默认未上架，
            //   下架它时索引里本来就没有），如果不判存在，这条路径会整个抛异常，
            //   导致同批其它商品也一起同步失败。
            //   多一次 existsById 的代价只发生在这条罕见路径上（写路径才是热路径），
            //   换来的是"删除操作天然幂等"——这比省一次往返重要得多。
            if (productRepository.existsById(id)) {
                productRepository.deleteById(id);
                LOGGER.info("商品已从索引移除（下架或删除）：id={}", id);
            } else {
                // 索引里本来就没有 → 目标状态已经达成，属于正常情况，不需要日志噪音
                LOGGER.debug("商品本就不在索引中，无需移除：id={}", id);
            }
        } else {
            // 按 id upsert：Spring Data ES 的 save 是「存在则覆盖、不存在则新建」，
            // 所以新增和修改走同一条路径，不需要区分
            productRepository.save(list.get(0));
            LOGGER.debug("商品索引已更新：id={} name={}", id, list.get(0).getName());
        }
    }

    /**
     * 批量收敛。
     *
     * 注意这里**逐条处理、让单条失败不影响其它条**，但最后会汇总失败数。
     * 为什么不用 saveAll 批量提交：批量写一旦中间失败，很难说清"哪些成功了"，
     * 而 ES 是允许延迟的，逐条写的开销在这个量级下完全可接受；
     * 换来的是**每条商品的成败都可追溯**。
     */
    @Override
    public int syncProducts(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return 0;
        }
        int changed = 0;
        List<Long> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                syncProduct(id);
                changed++;
            } catch (Exception e) {
                failed.add(id);
                LOGGER.error("商品索引收敛失败：id={}", id, e);
            }
        }
        if (!failed.isEmpty()) {
            // 有失败就抛出去，让上层（对账任务/消费者）知道这批没处理干净。
            // 静默吞掉失败会让"对账显示 0 差异"变成一句假话。
            throw new IllegalStateException("有 " + failed.size() + " 个商品索引收敛失败：" + failed);
        }
        return changed;
    }

    @Override
    public EsProduct create(Long id) {
        EsProduct result = null;
        List<EsProduct> esProductList = productDao.getAllEsProductList(id);
        if (esProductList.size() > 0) {
            EsProduct esProduct = esProductList.get(0);
            result = productRepository.save(esProduct);
        }
        return result;
    }

    @Override
    public void delete(List<Long> ids) {
        if (!CollectionUtils.isEmpty(ids)) {
            List<EsProduct> esProductList = new ArrayList<>();
            for (Long id : ids) {
                EsProduct esProduct = new EsProduct();
                esProduct.setId(id);
                esProductList.add(esProduct);
            }
            productRepository.deleteAll(esProductList);
        }
    }

    @Override
    public Page<EsProduct> search(String keyword, Integer pageNum, Integer pageSize) {
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        return productRepository.findByNameOrSubTitleOrKeywords(keyword, keyword, keyword, pageable);
    }

    /** ES 默认的 max_result_window：from + size 超过它就会直接报错 */
    private static final int MAX_RESULT_WINDOW = 10000;
    /** 单页最大条数，防止有人用 size=100000 绕过上面的限制 */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 判断这次翻页是否超出 ES 能承受的深度。
     *
     * 说明一下这里的处理取舍：
     *   现在的做法是「拦截 + 返回空页 + 打告警日志」，属于"让接口不 500"的止血方案。
     *   真正要支持深翻页，应该改造成 search_after 游标分页：
     *     · 第一页仍然用 from/size，但排序必须是一个稳定且唯一的组合
     *       （比如 price asc, id asc —— 只按 price 排，同价商品顺序不稳定，游标会漏数据）；
     *     · 把最后一条的排序值作为游标返回给前端；
     *     · 后续翻页带上这个游标，ES 从游标位置继续往后取，不再受 10000 限制。
     *   之所以没直接做游标，是因为它需要前端配合改交互（不能用"跳到第 N 页"），
     *   属于接口契约变更，不适合和本次缺陷修复一起做。
     */
    private boolean exceedsMaxResultWindow(Integer pageNum, Integer pageSize) {
        if (pageNum == null || pageSize == null || pageNum < 0 || pageSize <= 0) {
            LOGGER.warn("搜索分页参数非法：pageNum={} pageSize={}", pageNum, pageSize);
            return true;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            LOGGER.warn("搜索单页条数超限：pageSize={}（上限 {}）", pageSize, MAX_PAGE_SIZE);
            return true;
        }
        int from = pageNum * pageSize;
        if (from + pageSize > MAX_RESULT_WINDOW) {
            LOGGER.warn("搜索翻页过深已拦截：from={} size={}（ES max_result_window={}）。"
                            + "如需支持深翻页请改造为 search_after 游标分页。",
                    from, pageSize, MAX_RESULT_WINDOW);
            return true;
        }
        return false;
    }

    @Override
    public Page<EsProduct> search(String keyword, Long brandId, Long productCategoryId, Integer pageNum, Integer pageSize,Integer sort) {
        // 深分页保护：ES 的 max_result_window 默认是 10000，
        // from + size 一旦超过它，ES 会直接抛异常 → 接口 500。
        // 前台搜索页虽然不会真的翻到第 1000 页，但爬虫或者构造请求很容易触发。
        if (exceedsMaxResultWindow(pageNum, pageSize)) {
            return new PageImpl<>(ListUtil.empty(), PageRequest.of(pageNum, pageSize), 0);
        }
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder();
        //分页
        nativeQueryBuilder.withPageable(pageable);
        //过滤
        if (brandId != null || productCategoryId != null) {
            Query boolQuery = QueryBuilders.bool(builder -> {
                if (brandId != null) {
                    builder.must(QueryBuilders.term(b -> b.field("brandId").value(brandId)));
                }
                if (productCategoryId != null) {
                    builder.must(QueryBuilders.term(b -> b.field("productCategoryId").value(productCategoryId)));
                }
                return builder;
            });
            nativeQueryBuilder.withFilter(boolQuery);
        }
        //搜索
        if (StrUtil.isEmpty(keyword)) {
            nativeQueryBuilder.withQuery(QueryBuilders.matchAll(builder -> builder));
        } else {
            List<FunctionScore> functionScoreList = new ArrayList<>();
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("name").query(keyword)))
                    .weight(10.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("subTitle").query(keyword)))
                    .weight(5.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("keywords").query(keyword)))
                    .weight(2.0)
                    .build());
            FunctionScoreQuery.Builder functionScoreQueryBuilder = QueryBuilders.functionScore()
                    .functions(functionScoreList)
                    .scoreMode(FunctionScoreMode.Sum)
                    .minScore(2.0);
            nativeQueryBuilder.withQuery(builder -> builder.functionScore(functionScoreQueryBuilder.build()));
        }
        //排序
        if(sort==1){
            //按新品从新到旧
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("id")));
        }else if(sort==2){
            //按销量从高到低
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("sale")));
        }else if(sort==3){
            //按价格从低到高
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.asc("price")));
        }else if(sort==4){
            //按价格从高到低
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("price")));
        }else{
            //只有用户「没有指定排序」时才按相关度排。
            //
            //原实现是把 _score 排序无条件追加在最后，问题有两点：
            //  ① 排序字段里一旦出现 _score，ES 就必须为每个命中文档计算打分，
            //     无法走「不算分」的优化路径 —— 尤其是 keyword 为空、
            //     只有过滤条件（品牌/分类）的场景，本来完全不需要打分。
            //  ② 语义被搅浑：用户点「按价格从低到高」时，返回的顺序里
            //     还掺了一层 _score 在里面，将来排查"为什么这两条同价商品顺序反了"会白费力气。
            nativeQueryBuilder.withSort(Sort.by(Sort.Order.desc("_score")));
        }
        NativeQuery nativeQuery = nativeQueryBuilder.build();
        LOGGER.info("DSL:{}", nativeQuery.getQuery().toString());
        SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQuery, EsProduct.class);
        if(searchHits.getTotalHits()<=0){
            return new PageImpl<>(ListUtil.empty(),pageable,0);
        }
        List<EsProduct> searchProductList = searchHits.stream().map(SearchHit::getContent).collect(Collectors.toList());
        return new PageImpl<>(searchProductList,pageable,searchHits.getTotalHits());
    }

    @Override
    public Page<EsProduct> recommend(Long id, Integer pageNum, Integer pageSize) {
        Pageable pageable = PageRequest.of(pageNum, pageSize);
        List<EsProduct> esProductList = productDao.getAllEsProductList(id);
        if (esProductList.size() > 0) {
            EsProduct esProduct = esProductList.get(0);
            String keyword = esProduct.getName();
            Long brandId = esProduct.getBrandId();
            Long productCategoryId = esProduct.getProductCategoryId();
            //构建查询条件
            NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder();
            //分页
            nativeQueryBuilder.withPageable(pageable);
            //用于过滤掉相同的商品
            nativeQueryBuilder.withFilter(QueryBuilders.bool(build -> build.mustNot(QueryBuilders.term(b->b.field("id").value(id)))));
            //根据商品标题、品牌、分类进行搜索
            List<FunctionScore> functionScoreList = new ArrayList<>();
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("name").query(keyword)))
                    .weight(8.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("subTitle").query(keyword)))
                    .weight(2.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("keywords").query(keyword)))
                    .weight(2.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("brandId").query(brandId)))
                    .weight(5.0)
                    .build());
            functionScoreList.add(new FunctionScore.Builder()
                    .filter(QueryBuilders.match(builder -> builder.field("productCategoryId").query(productCategoryId)))
                    .weight(3.0)
                    .build());
            FunctionScoreQuery.Builder functionScoreQueryBuilder = QueryBuilders.functionScore()
                    .functions(functionScoreList)
                    .scoreMode(FunctionScoreMode.Sum)
                    .minScore(2.0);
            nativeQueryBuilder.withQuery(builder -> builder.functionScore(functionScoreQueryBuilder.build()));
            NativeQuery nativeQuery = nativeQueryBuilder.build();
            LOGGER.info("DSL:{}", nativeQuery.getQuery().toString());
            SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQuery, EsProduct.class);
            if(searchHits.getTotalHits()<=0){
                return new PageImpl<>(ListUtil.empty(),pageable,0);
            }
            List<EsProduct> searchProductList = searchHits.stream().map(SearchHit::getContent).collect(Collectors.toList());
            return new PageImpl<>(searchProductList,pageable,searchHits.getTotalHits());
        }
        return new PageImpl<>(ListUtil.empty());
    }

    @Override
    public EsProductRelatedInfo searchRelatedInfo(String keyword) {
        NativeQueryBuilder nativeQueryBuilder = new NativeQueryBuilder();
        //搜索条件
        if(StrUtil.isEmpty(keyword)){
            nativeQueryBuilder.withQuery(QueryBuilders.matchAll(builder -> builder));
        }else{
            nativeQueryBuilder.withQuery(QueryBuilders.multiMatch(builder -> builder.fields("name","subTitle","keywords").query(keyword)));
        }
        //聚合搜索品牌名称
        nativeQueryBuilder.withAggregation("brandNames",AggregationBuilders.terms(builder -> builder.field("brandName").size(10)));
        //聚合搜索分类名称
        nativeQueryBuilder.withAggregation("productCategoryNames",AggregationBuilders.terms(builder -> builder.field("productCategoryName").size(10)));
        //聚合搜索商品属性，去除type=0的属性
        Aggregation aggregation = new Aggregation.Builder().nested(builder -> builder.path("attrValueList"))
                .aggregations("productAttrs",new Aggregation.Builder()
                        .filter(b->b.term(a->a.field("attrValueList.type").value("1")))
                        .aggregations("attrIds",new Aggregation.Builder().terms(b->b.field("attrValueList.productAttributeId").size(10))
                                .aggregations("attrValues",new Aggregation.Builder().terms(b->b.field("attrValueList.value").size(10)).build())
                                .aggregations("attrNames",new Aggregation.Builder().terms(b->b.field("attrValueList.name").size(10)).build())
                                .build()).build()).build();
        nativeQueryBuilder.withAggregation("allAttrValues",aggregation);
        NativeQuery nativeQuery = nativeQueryBuilder.build();
        LOGGER.info("DSL:{}", nativeQueryBuilder.getQuery().toString());
        SearchHits<EsProduct> searchHits = elasticsearchTemplate.search(nativeQuery, EsProduct.class);
        return convertProductRelatedInfo(searchHits);
    }

    /**
     * 将返回结果转换为对象
     */
    private EsProductRelatedInfo convertProductRelatedInfo(SearchHits<EsProduct> response) {
        EsProductRelatedInfo productRelatedInfo = new EsProductRelatedInfo();
        Map<String, ElasticsearchAggregation> esAggregationMap = ((ElasticsearchAggregations) response.getAggregations()).aggregationsAsMap();
        //设置品牌
        ElasticsearchAggregation brandNames = esAggregationMap.get("brandNames");
        List<String> brandNameList = new ArrayList<>();
        List<StringTermsBucket> brandNameBuckets = ((StringTermsAggregate) brandNames.aggregation().getAggregate()._get()).buckets().array();
        for(int i = 0; i<brandNameBuckets.size(); i++){
            brandNameList.add(brandNameBuckets.get(i).key().stringValue());
        }
        productRelatedInfo.setBrandNames(brandNameList);
        //设置分类
        ElasticsearchAggregation productCategoryNames = esAggregationMap.get("productCategoryNames");
        List<String> productCategoryNameList = new ArrayList<>();
        List<StringTermsBucket> productCategoryNameBuckets = ((StringTermsAggregate) productCategoryNames.aggregation().getAggregate()._get()).buckets().array();
        for(int i = 0; i<productCategoryNameBuckets.size(); i++){
            productCategoryNameList.add(productCategoryNameBuckets.get(i).key().stringValue());
        }
        productRelatedInfo.setProductCategoryNames(productCategoryNameList);
        //设置参数
        ElasticsearchAggregation productAttrs = esAggregationMap.get("allAttrValues");
        List<LongTermsBucket> attrIdBuckets = ((LongTermsAggregate) ((FilterAggregate) ((NestedAggregate) productAttrs.aggregation().getAggregate()._get()).aggregations().get("productAttrs")._get()).aggregations().get("attrIds")._get()).buckets().array();
        List<EsProductRelatedInfo.ProductAttr> attrList = new ArrayList<>();
        for (LongTermsBucket item : attrIdBuckets) {
            EsProductRelatedInfo.ProductAttr attr = new EsProductRelatedInfo.ProductAttr();
            attr.setAttrId(item.key());
            List<String> attrValueList = new ArrayList<>();
            List<StringTermsBucket> attrValues = ((StringTermsAggregate) item.aggregations().get("attrValues")._get()).buckets().array();
            List<StringTermsBucket> attrNames = ((StringTermsAggregate) item.aggregations().get("attrNames")._get()).buckets().array();
            for (StringTermsBucket attrValue : attrValues) {
                attrValueList.add(attrValue.key().stringValue());
            }
            attr.setAttrValues(attrValueList);
            if(!CollectionUtils.isEmpty(attrNames)){
                String attrName = attrNames.get(0).key().stringValue();
                attr.setAttrName(attrName);
            }
            attrList.add(attr);
        }
        productRelatedInfo.setProductAttrs(attrList);
        return productRelatedInfo;
    }
}
