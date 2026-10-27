package com.macro.mall.portal.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 运行时参数。
 *
 * Created 2026/10/27.
 */
@Component
@ConfigurationProperties(prefix = "mall.ai.rag")
public class RagProperties {

    /** 是否启用 RAG。关闭后知识库不加载、检索接口不装配，已有功能完全不受影响。 */
    private boolean enabled = true;

    /** 启动时是否自动加载知识库。 */
    private boolean loadOnStartup = true;

    /**
     * 检索返回的知识块数量（topK）。
     *
     * 为什么不是越大越好：每个知识块都会进 prompt，topK=10 时
     * prompt 会多出上千 token，成本上升；更糟的是**引入噪音** ——
     * 检索排名靠后的块往往与问题无关，模型可能拿它当真去编。
     * 客服场景下 3 条足够覆盖一条政策（政策通常就一两句话）。
     */
    private int topK = 3;

    /**
     * 相似度阈值。低于此值的块直接丢弃。
     *
     * 为什么必须有：向量检索**永远会返回 topK 条**，哪怕问的问题知识库里根本没有。
     * 没有阈值的话，用户问「今天天气怎么样」，系统也会硬找三条最像的政策塞给模型，
     * 模型就只能硬编答案。
     * 设阈值后，检索不到像的就返回空，让模型老实说「这个问题我不知道」。
     */
    private double similarityThreshold = 0.5;

    /** 检索结果在 prompt 里的最大字符数，超出截断（防止超长知识块撑爆上下文）。 */
    private int maxContextChars = 3000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLoadOnStartup() {
        return loadOnStartup;
    }

    public void setLoadOnStartup(boolean loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }
}
