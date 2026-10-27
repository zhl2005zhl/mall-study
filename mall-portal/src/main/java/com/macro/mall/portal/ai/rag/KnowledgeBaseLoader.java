package com.macro.mall.portal.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服知识库加载器：把 Markdown 知识库切块、向量化、写入 Milvus。
 *
 * ============================ 分块策略：按「## 标题」切 ============================
 * 知识库的写法是每块以 `## ` 开头、块内自包含。这样切的好处：
 *
 *   1. **语义完整**：每块是一个完整的业务规则（"退货运费谁承担"），
 *      不会出现「前半句在块 A、后半句在块 B」的割裂
 *   2. **粒度合适**：一块通常 100-300 字，既够模型回答，又不会塞爆上下文
 *   3. **可引用**：检索命中后能直接告诉用户「依据是哪条政策」
 *
 * 为什么不用「固定长度切分（如每 500 字一刀）」：
 * 那种切法会把一条规则从中间劈开，检索到的半句话模型没法用，
 * 反而制造噪音 —— RAG 里「检索到无关内容」比「检索不到」更糟，
 * 因为模型会拿它当真去编答案。
 *
 * ============================ 为什么用 ApplicationRunner 而不是命令行 ============================
 * 知识库是**启动时就要就绪**的基础数据，不是按需触发的操作。
 * 用 ApplicationRunner 让它在 Bean 初始化后自动跑一次，
 * 省掉「先手动调接口导数据」这个容易忘的步骤。
 *
 * ============================ 幂等与可重复执行 ============================
 * 用固定的 document id（块标题的 hash）来避免重复导入：
 * 重复启动时覆盖同 id 的文档，而不是无限追加。
 * 否则每重启一次知识库就翻一倍，检索结果里全是重复内容。
 *
 * Created 2026/10/27.
 */
@Component
@ConditionalOnProperty(name = "mall.ai.rag.enabled", havingValue = "true")
public class KnowledgeBaseLoader implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeBaseLoader.class);

    /** 知识库文件位置（classpath）。 */
    private static final String KB_PATH = "ai/knowledge/客服知识库.md";

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final RagProperties ragProperties;

    public KnowledgeBaseLoader(ObjectProvider<VectorStore> vectorStoreProvider,
                               RagProperties ragProperties) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.ragProperties = ragProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ragProperties.isLoadOnStartup()) {
            LOGGER.info("[RAG] 跳过知识库自动加载（mall.ai.rag.load-on-startup=false）");
            return;
        }

        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            LOGGER.warn("[RAG] 没有可用的 VectorStore（Milvus 未配置或不可达），跳过知识库加载");
            return;
        }

        try {
            List<Document> docs = loadAndChunk();
            if (docs.isEmpty()) {
                LOGGER.warn("[RAG] 知识库内容为空，跳过");
                return;
            }

            // 先删旧的同 id 文档再写，保证幂等（重复启动不会翻倍）
            List<String> ids = new ArrayList<>();
            for (Document d : docs) {
                ids.add(d.getId());
            }
            try {
                vectorStore.delete(ids);
            } catch (Exception e) {
                // 首次运行索引为空，delete 可能报「id 不存在」，属正常，忽略
                LOGGER.debug("[RAG] 预删除旧知识块时出现异常（首次运行属正常）: {}", e.getMessage());
            }

            vectorStore.add(docs);
            LOGGER.info("[RAG] 知识库加载完成：{} 个知识块已写入向量库", docs.size());
        } catch (Exception e) {
            // 知识库加载失败不该阻止应用启动 —— RAG 是增强能力，不是核心依赖
            LOGGER.error("[RAG] 知识库加载失败（不影响应用启动，RAG 将不可用）", e);
        }
    }

    /**
     * 读取知识库文件并按 `## ` 切块。
     */
    private List<Document> loadAndChunk() throws Exception {
        ClassPathResource resource = new ClassPathResource(KB_PATH);
        if (!resource.exists()) {
            LOGGER.warn("[RAG] 找不到知识库文件: {}", KB_PATH);
            return new ArrayList<>();
        }

        List<Document> docs = new ArrayList<>();
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## ")) {
                    // 遇到新标题，先把上一块收尾
                    if (currentTitle != null) {
                        docs.add(buildDocument(currentTitle, currentBody.toString()));
                    }
                    currentTitle = line.substring(3).trim();
                    currentBody = new StringBuilder();
                } else if (currentTitle != null) {
                    currentBody.append(line).append('\n');
                }
                // 第一个 ## 之前的内容（说明性文字）不入库 —— 那是给人看的写作指引
            }
            if (currentTitle != null) {
                docs.add(buildDocument(currentTitle, currentBody.toString()));
            }
        }
        return docs;
    }

    /**
     * 构造一条知识块文档。
     *
     * 为什么把标题拼进正文：向量检索只看到 content 字段的实际文本。
     * 如果正文里没有标题，搜「退货运费」时可能匹配不到运费那一块
     * （正文里写的是"由买家承担"，没出现"退货运费"这几个字）。
     * 把标题拼进去，等于给每块加了一个强语义锚点。
     *
     * metadata 里单独存 title，方便检索后展示「依据：xxx」。
     */
    private Document buildDocument(String title, String body) {
        String trimmed = body.trim();
        // 标题 + 正文一起参与向量化
        String content = "【" + title + "】\n" + trimmed;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", title);
        metadata.put("source", KB_PATH);
        metadata.put("type", "customer-service-policy");

        // 用标题的 hash 当固定 id，保证重复导入时覆盖而不是追加
        String id = "kb-" + Integer.toHexString(title.hashCode());
        return new Document(id, content, metadata);
    }
}
