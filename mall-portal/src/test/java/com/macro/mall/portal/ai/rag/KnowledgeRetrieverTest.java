package com.macro.mall.portal.ai.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 检索逻辑测试（不依赖 Ollama / Milvus，纯 Mock）。
 *
 * ============================ 测什么 ============================
 * RAG 里最容易出错的不是「向量算得对不对」（那是模型的事），
 * 而是**检索结果的边界处理**——这决定了模型会不会胡编：
 *
 *   1. 检索不到时必须返回空，不能硬凑 → 否则模型拿噪音编答案
 *   2. 检索异常时必须降级，不能抛 → 否则整个对话挂掉
 *   3. 拼接上下文时要有长度上限 → 否则长知识块挤爆窗口
 *   4. 上下文里必须标明来源标题 → 否则模型说不清依据
 *
 * 这四件事用 Mock 就能验，不需要真跑 embedding。
 *
 * Created 2026/10/27.
 */
class KnowledgeRetrieverTest {

    private VectorStore vectorStore;
    private RagProperties props;
    private KnowledgeRetriever retriever;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStore = mock(VectorStore.class);
        props = new RagProperties();
        props.setTopK(3);
        props.setSimilarityThreshold(0.5);
        props.setMaxContextChars(3000);

        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vectorStore);

        retriever = new KnowledgeRetriever(provider, props);
    }

    // ==================================================================
    // 一、边界：检索不到必须返回空
    // ==================================================================

    @Test
    @DisplayName("检索无结果时返回空列表，绝不硬凑内容")
    void retrieve_returnsEmptyWhenNothingFound() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(new ArrayList<>());

        List<KnowledgeRetriever.RetrievedChunk> r = retriever.retrieve("今天天气怎么样");

        assertTrue(r.isEmpty(),
                "知识库里没有相关内容时必须返回空 —— 硬凑几条会让模型拿无关政策编答案");
    }

    @Test
    @DisplayName("检索抛异常时降级为空，不向上抛")
    void retrieve_degradesOnException() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("Milvus 连接失败"));

        // 不应抛异常 —— RAG 是增强能力，挂了不该拖垮整个对话
        List<KnowledgeRetriever.RetrievedChunk> r = retriever.retrieve("能退货吗");

        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName("空问题直接返回空，不浪费一次向量查询")
    void retrieve_blankQuery() {
        assertTrue(retriever.retrieve("").isEmpty());
        assertTrue(retriever.retrieve("   ").isEmpty());
        assertTrue(retriever.retrieve(null).isEmpty());
    }

    @Test
    @DisplayName("VectorStore 不存在时（RAG 未启用）返回空而不是报错")
    @SuppressWarnings("unchecked")
    void retrieve_withoutVectorStore() {
        ObjectProvider<VectorStore> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        KnowledgeRetriever r = new KnowledgeRetriever(emptyProvider, props);

        assertTrue(r.retrieve("能退货吗").isEmpty());
    }

    // ==================================================================
    // 二、正常检索
    // ==================================================================

    @Test
    @DisplayName("命中时返回带标题的结果")
    void retrieve_returnsChunksWithTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("title", "退货政策：七天无理由退货");
        Document doc = new Document("kb-1", "【退货政策：七天无理由退货】\n自签收次日起 7 天内...", meta);

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        List<KnowledgeRetriever.RetrievedChunk> r = retriever.retrieve("七天内能退吗");

        assertEquals(1, r.size());
        assertEquals("退货政策：七天无理由退货", r.get(0).getTitle());
        assertTrue(r.get(0).getContent().contains("7 天"));
    }

    // ==================================================================
    // 三、上下文拼接
    // ==================================================================

    @Test
    @DisplayName("拼上下文时标明来源标题，让模型能说清依据")
    void buildContext_includesTitle() {
        List<KnowledgeRetriever.RetrievedChunk> chunks = List.of(
                new KnowledgeRetriever.RetrievedChunk("退货政策：退货运费谁承担",
                        "质量问题商家承担运费，无理由退货买家承担。", 0.9));

        String ctx = retriever.buildContext(chunks);

        assertTrue(ctx.contains("退货政策：退货运费谁承担"), "必须带来源标题，否则模型说不清依据");
        assertTrue(ctx.contains("质量问题商家承担运费"));
        // 必须提示「仅在与问题相关时使用」，防止模型把无关政策硬套
        assertTrue(ctx.contains("仅在与用户问题相关时") || ctx.contains("相关"),
                "应提示模型只在与问题相关时使用资料");
    }

    @Test
    @DisplayName("上下文超过字符上限时截断并注明")
    void buildContext_truncatesWhenTooLong() {
        props.setMaxContextChars(200);

        List<KnowledgeRetriever.RetrievedChunk> chunks = List.of(
                new KnowledgeRetriever.RetrievedChunk("标题A", "内容".repeat(200), 0.9),
                new KnowledgeRetriever.RetrievedChunk("标题B", "内容".repeat(200), 0.8));

        String ctx = retriever.buildContext(chunks);

        assertTrue(ctx.length() <= 200 + 200, "应受 maxContextChars 限制，实际长度=" + ctx.length());
        assertTrue(ctx.contains("截断") || ctx.contains("未展示"),
                "截断时必须注明，让模型知道资料不完整");
    }

    @Test
    @DisplayName("空结果时返回空字符串（不产生无意义的提示语）")
    void buildContext_emptyChunks() {
        assertEquals("", retriever.buildContext(new ArrayList<>()));
        assertEquals("", retriever.buildContext(null));
    }
}
