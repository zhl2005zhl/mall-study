package com.macro.mall.portal.ai.agent;

import com.macro.mall.portal.ai.domain.AgentTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息历史裁剪的逻辑测试。
 *
 * ============================ 为什么这段逻辑值得单独测 ============================
 * 「上下文爆炸」是 Agent 的头号故障，但它的**失败方式是渐进的**：
 *   前几轮都正常 → 某一轮突然报 context length exceeded → 用户看到 500。
 * 你很难在开发时遇到（本地测试往往只问一两句），上线后被长对话打出来。
 *
 * 而这段逻辑有两个容易写错的点，都靠人工 review 很容易看漏：
 *   1. **成对关系**：assistant(工具调用) 与 tool(工具结果) 必须同生共死。
 *      砍掉 assistant 却留下 tool 响应，多数模型会直接 400。
 *   2. **字符 vs 条数**：只按条数裁，遇到「20 条里有 10 条几万字符的工具结果」照样爆。
 *
 * 这里通过反射直接测私有方法 —— 因为它是纯函数（输入 List<Message>，输出是否裁剪过），
 * 用反射测试比为了可测性去改生产代码的可见性更划算。
 *
 * Created 2026/10/27.
 */
class HistoryTrimTest {

    /** 构造一个控制器实例，只为了调用它的私有裁剪方法。 */
    private AiAgentController newController(int maxMessages, int maxChars, int maxObsChars) {
        AgentProperties props = new AgentProperties();
        props.setMaxHistoryMessages(maxMessages);
        props.setMaxHistoryChars(maxChars);
        props.setMaxObservationChars(maxObsChars);
        // 用 Mock 的 ChatModel 满足构造器断言（本测试不真正调模型，只用裁剪逻辑）
        org.springframework.ai.chat.model.ChatModel mockModel =
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        // RAG 未启用时 KnowledgeRetriever 为 null，用 ObjectProvider 返回 null 模拟
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<
                com.macro.mall.portal.ai.rag.KnowledgeRetriever> noRag =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(noRag.getIfAvailable()).thenReturn(null);
        return new AiAgentController(mockModel, null, null, props,
                new AgentTraceRecorder(null, props), noRag, "test-model");
    }

    private boolean invokeTrim(AiAgentController c, List<org.springframework.ai.chat.messages.Message> history)
            throws Exception {
        Method m = AiAgentController.class.getDeclaredMethod("trimHistory", List.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(c, history);
    }

    private int invokeMessageLength(AiAgentController c, org.springframework.ai.chat.messages.Message m)
            throws Exception {
        Method method = AiAgentController.class.getDeclaredMethod(
                "messageLength", org.springframework.ai.chat.messages.Message.class);
        method.setAccessible(true);
        return (Integer) method.invoke(c, m);
    }

    @Test
    @DisplayName("未超限时不裁剪")
    void noTrimWhenWithinLimits() throws Exception {
        AiAgentController c = newController(20, 100000, 6000);
        List<org.springframework.ai.chat.messages.Message> h = new ArrayList<>();
        h.add(new org.springframework.ai.chat.messages.UserMessage("你好"));
        h.add(new org.springframework.ai.chat.messages.AssistantMessage("你好，有什么可以帮你"));

        assertFalse(invokeTrim(c, h), "未超限不应裁剪");
        assertEquals(2, h.size());
    }

    @Test
    @DisplayName("超过字符上限时必须裁剪（即使消息条数很少）")
    void trimsByCharsNotJustCount() throws Exception {
        // 条数上限很大，但字符上限很小 —— 模拟「几条巨无霸消息」
        AiAgentController c = newController(20, 200, 600000);

        String big = "x".repeat(500);
        List<org.springframework.ai.chat.messages.Message> h = new ArrayList<>();
        h.add(new org.springframework.ai.chat.messages.UserMessage("第一个问题"));
        h.add(new org.springframework.ai.chat.messages.AssistantMessage(big));
        h.add(new org.springframework.ai.chat.messages.UserMessage("第二个问题"));
        h.add(new org.springframework.ai.chat.messages.AssistantMessage(big));

        assertTrue(invokeTrim(c, h), "总字符超限应触发裁剪");
        // 裁剪后应当变小（丢掉了第一个问答）
        long chars = 0;
        for (org.springframework.ai.chat.messages.Message m : h) {
            chars += invokeMessageLength(c, m);
        }
        assertTrue(chars <= 200 + big.length(), "裁剪后字符数应下降");
    }

    @Test
    @DisplayName("裁剪必须按 UserMessage 边界整段丢，不能把工具调用和结果拆散")
    void trimKeepsToolCallPairsIntact() throws Exception {
        AiAgentController c = newController(4, 100000, 6000);

        List<org.springframework.ai.chat.messages.Message> h = new ArrayList<>();
        // 第一轮完整问答（user + assistant + tool）
        h.add(new org.springframework.ai.chat.messages.UserMessage("Q1"));
        h.add(new org.springframework.ai.chat.messages.AssistantMessage("A1"));
        h.add(org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "id1", "queryMyOrders", "{}")))
                .build());
        // 第二轮
        h.add(new org.springframework.ai.chat.messages.UserMessage("Q2"));
        h.add(new org.springframework.ai.chat.messages.AssistantMessage("A2"));
        h.add(org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "id2", "queryMyOrders", "{}")))
                .build());

        assertTrue(invokeTrim(c, h), "6 条 > 上限 4，应裁剪");

        // 关键：裁剪后第一条不应是 ToolResponseMessage（那意味着配对的 assistant 被砍掉了）
        assertFalse(h.get(0) instanceof org.springframework.ai.chat.messages.ToolResponseMessage,
                "裁剪后首条不能是工具响应 —— 否则成对关系被破坏，模型会报 400");
        assertEquals(3, h.size(), "应丢掉第一轮整段，剩第二轮 3 条");
    }

    @Test
    @DisplayName("只剩单轮时不再裁剪（避免把唯一的上下文也丢掉）")
    void neverTrimLastRound() throws Exception {
        AiAgentController c = newController(2, 100000, 6000);

        List<org.springframework.ai.chat.messages.Message> h = new ArrayList<>();
        h.add(new org.springframework.ai.chat.messages.UserMessage("Q"));
        h.add(new org.springframework.ai.chat.messages.AssistantMessage("A"));
        h.add(org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "id", "t", "{}")))
                .build());

        // 3 条 > 上限 2，但没有第二个 UserMessage 可作截断点 —— 应保持不变
        invokeTrim(c, h);
        assertTrue(h.size() >= 1, "至少保留内容，不能清空");
        assertTrue(h.get(0) instanceof org.springframework.ai.chat.messages.UserMessage,
                "首条应仍是用户消息");
    }

    @Test
    @DisplayName("ToolResponseMessage 的长度要计入 responses 正文，不能只算 getText()")
    void toolResponseLengthCountsResponseData() throws Exception {
        AiAgentController c = newController(20, 100000, 6000);

        String payload = "y".repeat(1000);
        org.springframework.ai.chat.messages.ToolResponseMessage trm =
                org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                        .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                                "id", "t", payload)))
                        .build();

        int len = invokeMessageLength(c, trm);
        assertTrue(len >= 1000,
                "工具响应正文必须计入长度，否则字符级裁剪会完全失效（实测 getText() 取不到正文），实际=" + len);
    }

    @Test
    @DisplayName("observation 超长时应截断并注明，而不是整体丢弃")
    void shrinkObservationTruncatesWithNotice() throws Exception {
        AiAgentController c = newController(20, 100000, 100);
        String longObs = "z".repeat(500);

        Method m = AiAgentController.class.getDeclaredMethod("shrinkObservation", String.class);
        m.setAccessible(true);
        String shrunk = (String) m.invoke(c, longObs);

        assertTrue(shrunk.length() < longObs.length(), "应被截断");
        assertTrue(shrunk.contains("截断"), "必须注明被截断 —— 否则模型会以为看到的就是全部");
        assertTrue(shrunk.contains("500"), "应告知原始长度，便于模型判断要不要缩小范围");
    }

    @Test
    @DisplayName("未超长的 observation 原样返回")
    void shrinkObservationKeepsShortAsIs() throws Exception {
        AiAgentController c = newController(20, 100000, 6000);
        String shortObs = "{\"success\":true}";
        Method m = AiAgentController.class.getDeclaredMethod("shrinkObservation", String.class);
        m.setAccessible(true);
        assertEquals(shortObs, m.invoke(c, shortObs));
    }
}
