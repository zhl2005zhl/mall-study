package com.macro.mall.portal.ai.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 执行的完整轨迹（trace）。
 *
 * ============================ 为什么要有这个东西 ============================
 *
 * Agent 是**概率性系统**：同一个问题问两次，可能走不同的路径、
 * 调不同次数工具、消耗不同 token。这意味着传统 CRUD 的调试方式彻底失效 ——
 * 「加个断点看看」没用，因为你根本不知道它这次会不会走那条路。
 *
 * 没有 trace 时的排查体验是：
 *   「用户说查不到订单」→ 它到底调工具了没？→ 不知道
 *   → 调了几次？→ 不知道 → 参数传对了吗？→ 不知道 → 工具返回了什么？→ 不知道
 * 只能靠猜。
 *
 * 有了 trace，一次执行可以完整复原：
 *   第 1 轮：模型想干什么、调了哪个工具、参数是什么、工具回了什么、花了多少 token
 *   第 2 轮：看到上一轮结果后又做了什么
 *   ...
 *   最终：几轮结束、为什么结束、总共多少 token、多少钱
 *
 * ============================ 为什么单独抽成类 ============================
 * 1. 可落盘：写成 JSON 一行一条，方便 grep / 聚合分析
 * 2. 可展示：面试时可以直接打开说「这是我 Agent 一次完整执行的 trace，
 *    3 轮完成，共消耗 X token」—— 这比嘴上讲「我做了个 Agent」有说服力得多
 * 3. 可度量：有了 token 和耗时的结构，才谈得上优化（否则改完不知道有没有变好）
 *
 * Created 2026/10/27.
 */
public class AgentTrace {

    /** trace 唯一 id，便于和一次请求对应上。 */
    private String traceId;

    /** 会话 id。 */
    private String sessionId;

    /** 用户原始问题。 */
    private String question;

    /** 最终回答。 */
    private String answer;

    /** 每轮的执行记录。 */
    private List<Step> steps = new ArrayList<>();

    /** 实际执行的轮数。 */
    private int rounds;

    /** 工具调用总次数。 */
    private long toolCallCount;

    /** 累计消耗的 token（prompt + completion）。 */
    private int totalTokens;
    private int promptTokens;
    private int completionTokens;

    /** 总耗时（毫秒）。 */
    private long costMs;

    /** 终止原因。 */
    private String stopReason;

    /** 是否成功走完（用于统计成功率）。 */
    private boolean success = true;

    /** 历史裁剪发生了几次 —— 用于观察上下文压力。 */
    private int historyTrimCount;

    /** 模型名，便于对比不同模型的成本。 */
    private String model;

    /** RAG 检索命中的知识块数量（0 表示没检索到，或 RAG 未启用）。 */
    private int ragHitCount;

    /** 命中的知识块标题（用 | 连接），用于判断「检索对没对」。 */
    private String ragTitles;

    /**
     * 单轮执行记录。
     *
     * thought / action / observation 就是 ReAct 的三要素，
     * 加上 token 与耗时，构成完整的一轮快照。
     */
    public static class Step {
        private int round;
        /** 模型的文本输出（推理或结论）。 */
        private String thought;
        /** 调用了哪个工具，参数是什么。 */
        private String action;
        /** 工具返回给模型的内容（**已瘦身**，不是原始全量）。 */
        private String observation;
        /** 该轮耗时。 */
        private long elapsedMs;
        /** 该轮 token 消耗。 */
        private int promptTokens;
        private int completionTokens;
        /** 该轮的 observation 字符数 —— 用来量化「瘦身省了多少」。 */
        private int observationChars;

        public Step() {
        }

        public int getRound() { return round; }
        public void setRound(int round) { this.round = round; }
        public String getThought() { return thought; }
        public void setThought(String thought) { this.thought = thought; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getObservation() { return observation; }
        public void setObservation(String observation) { this.observation = observation; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
        public int getObservationChars() { return observationChars; }
        public void setObservationChars(int observationChars) { this.observationChars = observationChars; }
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps; }
    public int getRounds() { return rounds; }
    public void setRounds(int rounds) { this.rounds = rounds; }
    public long getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(long toolCallCount) { this.toolCallCount = toolCallCount; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public int getHistoryTrimCount() { return historyTrimCount; }
    public void setHistoryTrimCount(int historyTrimCount) { this.historyTrimCount = historyTrimCount; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getRagHitCount() { return ragHitCount; }
    public void setRagHitCount(int ragHitCount) { this.ragHitCount = ragHitCount; }
    public String getRagTitles() { return ragTitles; }
    public void setRagTitles(String ragTitles) { this.ragTitles = ragTitles; }
}
