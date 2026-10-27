package com.macro.mall.portal.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索服务 —— RAG 里的那个 "R"（Retrieval）。
 *
 * ============================ RAG 到底是什么 ============================
 * 一句话：**先查资料，再让模型带着资料回答**。
 *
 * 不这么做的话，模型只有两种选择：
 *   a) 用它训练时的记忆编（客服政策这种"你家的具体规定"它不可能知道）
 *   b) 老实说「我不知道」
 *
 * 有了 RAG，就能把「我家能退几天」「运费谁出」这类**私有知识**喂到它眼前，
 * 让它基于真实资料回答，而不是编。
 *
 * ============================ 这一步最容易做错的两件事 ============================
 *
 * ① 不设相似度阈值
 *    向量检索**永远会返回 topK 条**，哪怕问的是「今天天气」它也会硬找三块最像的政策。
 *    没有阈值 → 检索结果必然非空 → 模型拿到一堆无关内容 → 拿着无关政策硬编答案。
 *    这比「检索不到」糟糕得多：检索不到时模型会说不知道，而拿到噪音时它会自信地胡说。
 *
 * ② 检索结果直接全塞进 prompt
 *    知识块太长会挤占上下文，而且**无关内容会稀释有效信息**。
 *    所以这里做了两件事：阈值过滤 + 总量截断。
 *
 * ============================ 为什么用 VectorStore 抽象而不是直接调 Milvus ============================
 * Spring AI 的 VectorStore 接口屏蔽了具体实现。将来要换 Qdrant / pgvector，
 * 只改依赖和配置，这个类一行都不用动。
 * 这也是为什么前面评估时没有为了让 ES 能用而去手写 script_score 适配器 ——
 * 顺着抽象走，比对抗它省事。
 *
 * Created 2026/10/27.
 */
@Service
@ConditionalOnProperty(name = "mall.ai.rag.enabled", havingValue = "true")
public class KnowledgeRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeRetriever.class);

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final RagProperties ragProperties;

    public KnowledgeRetriever(ObjectProvider<VectorStore> vectorStoreProvider,
                              RagProperties ragProperties) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.ragProperties = ragProperties;
    }

    /**
     * 检索与问题相关的知识块。
     *
     * @return 命中的知识块；**检索不到时返回空列表，而不是硬凑内容**。
     *         调用方（Agent）应据此决定"知识库里没有"而不是编造答案。
     */
    public List<RetrievedChunk> retrieve(String query) {
        List<RetrievedChunk> result = new ArrayList<>();

        if (query == null || query.trim().isEmpty()) {
            return result;
        }

        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            LOGGER.debug("[RAG] 没有可用的 VectorStore，跳过检索");
            return result;
        }

        try {
            // 用相似度阈值过滤：低于阈值的块（与问题无关）直接丢弃。
            // 这是 RAG 效果的关键开关 —— 见类注释的「最容易做错的两件事」。
            SearchRequest request = SearchRequest.builder()
                    .query(query.trim())
                    .topK(ragProperties.getTopK())
                    .similarityThreshold(ragProperties.getSimilarityThreshold())
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);
            if (docs == null || docs.isEmpty()) {
                LOGGER.info("[RAG] 未检索到相关知识（query={}，阈值={}）",
                        truncate(query, 40), ragProperties.getSimilarityThreshold());
                return result;
            }

            for (Document doc : docs) {
                String title = doc.getMetadata() != null
                        ? String.valueOf(doc.getMetadata().getOrDefault("title", "未命名"))
                        : "未命名";
                result.add(new RetrievedChunk(title, doc.getText(), doc.getScore()));
            }

            LOGGER.info("[RAG] 检索命中 {} 条（query={}）: {}", result.size(),
                    truncate(query, 40),
                    result.stream().map(RetrievedChunk::getTitle).reduce((a, b) -> a + " | " + b).orElse(""));

            return result;
        } catch (Exception e) {
            // 检索失败不该让对话失败：降级成"没有检索到"，让模型走无知识库路径
            LOGGER.error("[RAG] 检索失败（将降级为无知识库回答）", e);
            return result;
        }
    }

    /**
     * 把检索结果拼成给模型的上下文文本。
     *
     * 格式设计要点：
     *   - 每条标明来源标题，让模型能说「依据是《退货政策：七天无理由退货》」
     *   - 有总长度上限，超出截断并注明 —— 防止长知识块挤爆上下文
     *   - 明确标注这是「参考资料」，提示模型"只在相关时使用"，
     *     避免它把不相关的政策硬套到问题上
     */
    public String buildContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是商城官方的客服政策资料，请**仅在与用户问题相关时**使用：\n\n");

        int total = 0;
        int max = ragProperties.getMaxContextChars();

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            String block = "【资料 " + (i + 1) + "： " + c.getTitle() + "】\n" + c.getContent() + "\n\n";

            if (total + block.length() > max) {
                // 超出上限：截断并说明，让模型知道资料不完整
                int remain = max - total;
                if (remain > 100) {
                    sb.append(block, 0, remain).append("…（资料过长已截断）\n");
                }
                sb.append("（后续资料因长度限制未展示）\n");
                break;
            }
            sb.append(block);
            total += block.length();
        }

        return sb.toString();
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /**
     * 检索命中的一个知识块。
     */
    public static class RetrievedChunk {
        /** 知识块标题（如「退货政策：退货运费谁承担」）。 */
        private final String title;
        /** 知识块正文。 */
        private final String content;
        /** 相似度分数。 */
        private final Double score;

        public RetrievedChunk(String title, String content, Double score) {
            this.title = title;
            this.content = content;
            this.score = score;
        }

        public String getTitle() { return title; }
        public String getContent() { return content; }
        public Double getScore() { return score; }
    }
}
