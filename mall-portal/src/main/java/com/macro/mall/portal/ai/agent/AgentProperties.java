package com.macro.mall.portal.ai.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行时参数。
 *
 * 这些参数**不是可选项**，每一个都对应设计文档里的一条失败模式：
 *   maxRounds      —— 没有它，模型陷入循环时会一直调工具，账单和延迟一起失控
 *   timeoutSeconds —— 没有它，一次慢查询会让整个请求挂死
 *   maxHistoryMessages —— 没有它，几轮之后上下文就爆了
 *
 * Created 2026/10/20.
 */
@Component
@ConfigurationProperties(prefix = "mall.ai.agent")
public class AgentProperties {

    /** 是否启用 AI 能力。关掉后相关 Bean 不生效，不影响原有业务。 */
    private boolean enabled = true;

    /**
     * 最大轮次（硬上限）。
     *
     * 这是最后的安全网。正常任务 2-3 轮就结束；
     * 给到 6 轮是留足余量，又不至于让失控的循环跑太久。
     */
    private int maxRounds = 6;

    /** 整体耗时上限（秒）。 */
    private int timeoutSeconds = 120;

    /** 滑动窗口：历史消息超过这个数量就把最早的丢掉。 */
    private int maxHistoryMessages = 20;

    /**
     * 历史字符数上限（更关键的那个限制）。
     *
     * 为什么"按条数"不够：一条 tool 响应可能几万字符。
     * 「20 条消息」听起来很少，但如果其中 10 条是工具结果，照样能到几十万字符、
     * 直接超出上下文窗口。真正的成本瓶颈是**字符/token 数**，不是条数。
     * 所以两个限制都要有：条数防"碎消息堆积"，字符数防"单条巨无霸"。
     */
    private int maxHistoryChars = 40000;

    /** 单条工具结果回填给模型时的字符上限，超出部分截断并注明。 */
    private int maxObservationChars = 6000;

    /** 是否把 trace 落盘成 JSONL。 */
    private boolean traceEnabled = true;

    /** trace 落盘目录。默认放 logs 下，不进版本库。 */
    private String traceDir = "logs/ai-trace";

    /** 是否把 trace 一并返回给调用方（演示/面试时可打开看完整路径）。 */
    private boolean exposeTrace = true;

    public int getMaxHistoryChars() {
        return maxHistoryChars;
    }

    public void setMaxHistoryChars(int maxHistoryChars) {
        this.maxHistoryChars = maxHistoryChars;
    }

    public int getMaxObservationChars() {
        return maxObservationChars;
    }

    public void setMaxObservationChars(int maxObservationChars) {
        this.maxObservationChars = maxObservationChars;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public void setTraceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }

    public String getTraceDir() {
        return traceDir;
    }

    public void setTraceDir(String traceDir) {
        this.traceDir = traceDir;
    }

    public boolean isExposeTrace() {
        return exposeTrace;
    }

    public void setExposeTrace(boolean exposeTrace) {
        this.exposeTrace = exposeTrace;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }
}
