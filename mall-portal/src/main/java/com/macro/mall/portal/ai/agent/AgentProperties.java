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
