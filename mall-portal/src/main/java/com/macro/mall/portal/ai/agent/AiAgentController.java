package com.macro.mall.portal.ai.agent;

import com.macro.mall.common.api.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 手写的 Agent Loop（ReAct 模式）—— mall 的 AI 客服入口。
 *
 * ============================ 为什么手写，而不用框架的自动执行 ============================
 * 框架（Spring AI / langchain4j）都能一行代码把工具循环跑起来。手写的理由有两个：
 *
 *  1. 求职价值：珠三角有 JD 明确写着「自行实现过 Agent Loop、Function Calling」。
 *     这句话筛的就是「你知不知道循环里面发生了什么」。
 *  2. 工程价值：框架把循环封在内部，出问题时是黑盒。手写过一遍才知道
 *     该在哪加日志、在哪限制轮次、在哪截断历史 —— 这些位置框架不会告诉你。
 *
 * ============================ 循环的四个终止条件 ============================
 * 这是「能跑」和「跑得稳」的分界线，缺一个就会出问题：
 *   1. 正常结束     模型不再要求调用工具 → 直接返回答案
 *   2. 最大轮次     硬上限，防止失控循环把账单和延迟一起拉爆
 *   3. 整体超时     单次慢调用不该让请求无限挂住
 *   4. 重复调用检测 模型反复用同样参数调同一个工具时，属于无效循环，要打断
 *
 * ============================ 错误回填，而不是抛异常 ============================
 * 工具失败时不终止整个流程，而是把**可读的错误信息**作为观察结果回填给模型，
 * 让它换一条路走。这是 Agent 比固定流程强的地方。
 * 前提是错误信息必须是人话 —— 所以 MallTools 里所有错误都被写成了
 * 「错在哪 + 正确格式 + 退路是什么」。
 *
 * ============================ 可观测性 ============================
 * 每轮都记录 thought / action / observation / 耗时。
 * Agent 是概率性的，同一个问题两次可能走不同路径 ——
 * 没有 trace，出错时你完全不知道它在第几轮、调了什么、拿到了什么。
 *
 * Created 2026/10/20.
 */
@Tag(name = "AiAgentController", description = "AI 智能客服（手写 Agent Loop）")
@RestController
@RequestMapping("/ai")
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class AiAgentController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiAgentController.class);

    private static final String SYSTEM_PROMPT = """
            你是 mall 商城的智能客服助手，帮用户查询订单和商品信息。

            ## 你的能力边界
            你只能通过工具查询**当前登录用户自己的**订单和公开的商品信息。
            你不能：查询他人订单、修改任何数据、执行退款、取消订单、修改地址。
            用户要求你做这些事时，明确告知做不到，并说明可以怎么做（例如自己去订单页操作）。

            ## 工具使用原则
            1. 先想清楚要查什么，再决定调用哪个工具。不要凭猜测回答数据类问题。
            2. 用户提到具体订单号时用 getOrderDetail；只描述订单特征（如"上周买的手机"）时，
               先用 queryMyOrders 列出订单，找到匹配的再查详情。
            3. 工具返回错误时，**认真读错误信息**，它通常写明了该怎么修正。
               比如提示订单号格式错误，就改用 queryMyOrders 拿正确的订单号，而不是重复调用。
            4. 拿到的数据里没有的信息，就直说没有，**绝不编造**订单号、金额、物流单号。

            ## 回答风格
            - 用中文，简洁，像真人客服，不要输出 JSON 或 Markdown 表格。
            - 涉及金额要写清币种（元）。
            - 回答完可以补一句下一步建议，但不要啰嗦。
            """;

    private final ChatModel chatModel;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ObjectProvider<com.macro.mall.portal.ai.tool.MallTools> mallToolsProvider;
    private final AgentProperties agentProperties;

    /**
     * 手动构建 ChatClient，不依赖 ChatClientAutoConfiguration。
     *
     * 原因：那个自动配置会**无条件**创建 ChatClient.Builder，而它依赖 ChatModel。
     * 无 Key 时 ChatModel 被关掉，于是每次请求都抛 NoSuchBeanDefinitionException，
     * 被全局异常处理成 401（看起来像鉴权失败，实为 bean 缺失）。
     * 与其在「排除自动配置」和「有 Key 时又需要它」之间反复权衡，
     * 不如直接自己 new —— ChatClient 只是个薄封装，没有任何魔法。
     */
    private final ChatClient chatClient;

    /**
     * 用于给「整体超时」做硬中断。
     *
     * 为什么必须有：一次模型调用可能因为网络卡住无限期挂着，
     * 而 Agent 循环是同步的，卡住就意味着线程一直被占。
     * 用单独的线程池 + Future.get(timeout) 是成本最低的实现方式。
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mall-ai-agent");
        t.setDaemon(true);
        return t;
    });

    /**
     * 会话历史。key 由调用方传（前端可用一个随机的 sessionId）。
     *
     * 【为什么暂用内存】这是本阶段的简化：单实例、重启即失。
     * 真实场景应该放 Redis（项目里本来就有），键带 TTL。
     * 这里刻意先不引入 Redis，是为了让「Agent 循环」这件事本身先跑通 ——
     * 范围别一次扩太大，否则出问题时不知道是新代码还是存储的问题。
     */
    private final Map<String, List<Message>> sessionHistory = new ConcurrentHashMap<>();

    public AiAgentController(ChatModel chatModel,
                             ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                             ObjectProvider<com.macro.mall.portal.ai.tool.MallTools> mallToolsProvider,
                             AgentProperties agentProperties) {
        this.chatModel = chatModel;
        this.toolCallbackProviders = toolCallbackProviders;
        this.mallToolsProvider = mallToolsProvider;
        this.agentProperties = agentProperties;
        // ChatClient.create 是官方的编程式入口，效果等价于自动配置产出的 Bean
        this.chatClient = ChatClient.create(chatModel);
    }

    /**
     * Agent 对话入口。
     *
     * 需要登录（走 mall 的 JWT）—— 因为工具内部要从 SecurityContext 取当前会员。
     */
    @Operation(summary = "AI 客服对话（手写 Agent Loop）")
    @PostMapping("/chat")
    public CommonResult<AiChatResult> chat(@RequestBody AiChatRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return CommonResult.failed("问题不能为空");
        }
        if (!agentProperties.isEnabled()) {
            return CommonResult.failed("AI 能力未启用");
        }

        // 模型存在性已由 @ConditionalOnProperty(spring.ai.model.chat=deepseek) 保证：
        // 没有 ChatModel 时本控制器根本不会被装配，改由 AiAgentFallbackController 接管。

        String question = request.getQuestion().trim();
        String sessionId = (request.getSessionId() == null || request.getSessionId().trim().isEmpty())
                ? "default" : request.getSessionId().trim();

        try {
            AiChatResult result = executor.submit(() -> runAgent(sessionId, question))
                    .get(agentProperties.getTimeoutSeconds(), TimeUnit.SECONDS);
            return CommonResult.success(result);
        } catch (TimeoutException e) {
            LOGGER.warn("[AI Agent] 整体超时 sessionId={} question={}", sessionId, question);
            return CommonResult.failed("处理超时（超过 " + agentProperties.getTimeoutSeconds()
                    + " 秒），请稍后重试或把问题拆得更具体一些");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommonResult.failed("请求被中断");
        } catch (ExecutionException e) {
            LOGGER.error("[AI Agent] 执行异常 sessionId={}", sessionId, e.getCause());
            return CommonResult.failed("AI 服务暂时不可用：" + shortMessage(e.getCause()));
        }
    }

    /**
     * 清空某个会话的历史。前端开新对话时调用。
     */
    @Operation(summary = "清空 AI 会话历史")
    @PostMapping("/chat/reset")
    public CommonResult<String> reset(@RequestBody(required = false) AiChatRequest request) {
        String sessionId = (request == null || request.getSessionId() == null)
                ? "default" : request.getSessionId().trim();
        sessionHistory.remove(sessionId);
        return CommonResult.success("已清空会话 " + sessionId);
    }

    // ==================================================================
    // 核心循环
    // ==================================================================

    private AiChatResult runAgent(String sessionId, String question) {
        long startMs = System.currentTimeMillis();

        // ---- 组装工具 ----
        List<ToolCallback> tools = resolveTools();
        if (tools.isEmpty()) {
            return failResult(question, "没有可用的查询工具，无法完成数据查询类问题。");
        }

        // ---- 组装历史（带滑动窗口）----
        List<Message> history = new ArrayList<>(
                sessionHistory.getOrDefault(sessionId, new ArrayList<>()));
        history.add(new UserMessage(question));

        ChatClient client = this.chatClient;

        // ---- trace：每轮记录 thought / action / observation ----
        List<AgentStep> steps = new ArrayList<>();
        // 重复调用检测：记录「工具名 + 参数」指纹
        Set<String> seenCalls = new HashSet<>();

        String finalAnswer = null;
        String stopReason = null;
        int round = 0;

        for (round = 1; round <= agentProperties.getMaxRounds(); round++) {
            long roundStart = System.currentTimeMillis();

            // ① 调模型（带上完整的工具清单和历史）
            ChatResponse response = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(history)
                    .toolCallbacks(tools)
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null) {
                stopReason = "模型返回为空";
                break;
            }

            Generation generation = response.getResult();
            AssistantMessage assistantMessage = generation.getOutput();
            history.add(assistantMessage);

            // ② 判断：模型是要调工具，还是给最终答案
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                finalAnswer = assistantMessage.getText();
                steps.add(new AgentStep(round, truncate(assistantMessage.getText()),
                        null, null, System.currentTimeMillis() - roundStart));
                stopReason = "正常结束";
                break;
            }

            // ③ 执行工具，把结果回填
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : toolCalls) {
                String callKey = call.name() + "|" + call.arguments();
                String observation;

                if (!seenCalls.add(callKey)) {
                    // 重复调用检测：同样的工具 + 同样的参数已经调过，结果不会变
                    observation = "【系统提示】你已经用完全相同的参数调用过 " + call.name()
                            + "，结果不会改变。请换个思路：要么换参数重试，要么基于已有结果直接回答用户。";
                    LOGGER.warn("[AI Agent] 检测到重复调用 sessionId={} round={} call={}",
                            sessionId, round, callKey);
                } else {
                    observation = executeTool(tools, call);
                }

                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), observation));

                steps.add(new AgentStep(round, null,
                        call.name() + "(" + truncate(call.arguments()) + ")",
                        truncate(observation),
                        System.currentTimeMillis() - roundStart));
            }
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());

            // ④ 滑动窗口：历史太长就丢掉最早的对话（保留 system 由 ChatClient 负责，
            //    这里只裁剪 messages 列表本身）
            trimHistory(history);
        }

        if (finalAnswer == null && stopReason == null) {
            // 跑满轮次还没给答案 —— 这是「最大轮次」兜底生效
            stopReason = "达到最大轮次上限（" + agentProperties.getMaxRounds() + "），强制终止";
            finalAnswer = "这个问题的处理步骤有点多，我暂时没能查完。"
                    + "可以试着把问题说得更具体一些（比如直接给订单号），我再帮你查一次。";
        }

        // ---- 保存历史（裁剪后）----
        trimHistory(history);
        sessionHistory.put(sessionId, history);

        AiChatResult result = new AiChatResult();
        result.setAnswer(finalAnswer);
        result.setSessionId(sessionId);
        result.setRounds(round);
        result.setSteps(steps);
        result.setStopReason(stopReason);
        result.setCostMs(System.currentTimeMillis() - startMs);
        result.setToolCallCount(steps.stream().filter(s -> s.getAction() != null).count());

        LOGGER.info("[AI Agent] 完成 sessionId={} 轮次={} 工具调用={} 耗时={}ms 终止原因={}",
                sessionId, round, result.getToolCallCount(), result.getCostMs(), stopReason);
        return result;
    }

    /**
     * 执行单个工具，**任何异常都转成可读观察结果**。
     *
     * 这是「错误回填而非抛异常」原则的落点：
     * 工具崩了不该让整个对话崩，而应该让模型知道失败了、并能换个方式。
     */
    private String executeTool(List<ToolCallback> tools, AssistantMessage.ToolCall call) {
        for (ToolCallback tool : tools) {
            if (tool.getToolDefinition().name().equals(call.name())) {
                try {
                    String result = tool.call(call.arguments());
                    return result == null ? "（工具返回空）" : result;
                } catch (Exception e) {
                    LOGGER.warn("[AI Agent] 工具执行异常 tool={} args={}",
                            call.name(), call.arguments(), e);
                    return "工具 " + call.name() + " 执行失败：" + shortMessage(e)
                            + "。请换个方式再试，或如实告诉用户这次没查到。";
                }
            }
        }
        return "没有名为 " + call.name() + " 的工具。"
                + "可用工具：queryMyOrders（查我的订单）、getOrderDetail（查订单详情）、"
                + "getProductStock（查商品库存）、searchProducts（搜商品）。";
    }

    /**
     * 收集工具回调。
     *
     * 两种来源都收：
     *  1. @Component 的 ToolCallbackProvider（Spring AI 自动发现的）
     *  2. 直接注册 MallTools 里的 @Tool 方法（本项目的主来源）
     *
     * 用 ToolCallbacks.from(...) 把带 @Tool 注解的 Bean 方法转成 ToolCallback。
     */
    private List<ToolCallback> resolveTools() {
        List<ToolCallback> tools = new ArrayList<>();

        com.macro.mall.portal.ai.tool.MallTools mallTools = mallToolsProvider.getIfAvailable();
        if (mallTools != null) {
            tools.addAll(List.of(org.springframework.ai.support.ToolCallbacks.from(mallTools)));
        }

        toolCallbackProviders.orderedStream().forEach(p -> {
            ToolCallback[] callbacks = p.getToolCallbacks();
            if (callbacks != null) {
                tools.addAll(List.of(callbacks));
            }
        });

        return tools;
    }

    /**
     * 滑动窗口裁剪。
     *
     * 注意：不能只按条数砍。tool 响应消息和它对应的 assistant(工具调用) 消息
     * 是成对的 —— 砍掉了 assistant 却留下 tool 响应，很多模型会直接报 400。
     * 所以这里按「从前往后整段丢」的方式，每次丢到下一个 UserMessage 开头。
     */
    private void trimHistory(List<Message> history) {
        int max = agentProperties.getMaxHistoryMessages();
        while (history.size() > max) {
            int cut = -1;
            for (int i = 1; i < history.size(); i++) {
                if (history.get(i) instanceof UserMessage) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) {
                // 找不到安全的截断点（说明只有一条用户消息 + 一堆工具往返），
                // 那就只保留第一条用户消息，避免消息成对关系被破坏
                Message first = history.get(0);
                history.clear();
                history.add(first);
                // 但第一条如果是 User 之后紧跟 tool 往返，答案会丢；
                // 这里让它自然结束，由下一次循环重新累积
                break;
            }
            history.subList(0, cut).clear();
        }
    }

    private AiChatResult failResult(String question, String reason) {
        AiChatResult r = new AiChatResult();
        r.setAnswer(reason);
        r.setStopReason("前置条件不满足");
        r.setSteps(new ArrayList<>());
        r.setRounds(0);
        r.setCostMs(0L);
        r.setToolCallCount(0L);
        return r;
    }

    private String shortMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "...(截断)" : s;
    }

    // ==================================================================
    // 请求 / 响应 / trace 结构
    // ==================================================================

    public static class AiChatRequest {
        /** 用户问题。 */
        private String question;
        /** 会话 id，前端生成。相同 id 共享对话历史。 */
        private String sessionId;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    public static class AiChatResult {
        /** 最终回答。 */
        private String answer;
        private String sessionId;
        /** 实际走了几轮。 */
        private int rounds;
        /** 每轮的动作记录 —— 这就是可观测性的产物。 */
        private List<AgentStep> steps;
        /** 终止原因：正常结束 / 达到最大轮次 / 前置条件不满足。 */
        private String stopReason;
        /** 总耗时（毫秒）。 */
        private Long costMs;
        /** 工具调用总次数。 */
        private Long toolCallCount;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public int getRounds() { return rounds; }
        public void setRounds(int rounds) { this.rounds = rounds; }
        public List<AgentStep> getSteps() { return steps; }
        public void setSteps(List<AgentStep> steps) { this.steps = steps; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
        public Long getCostMs() { return costMs; }
        public void setCostMs(Long costMs) { this.costMs = costMs; }
        public Long getToolCallCount() { return toolCallCount; }
        public void setToolCallCount(Long toolCallCount) { this.toolCallCount = toolCallCount; }
    }

    /**
     * 单轮执行记录。thought / action / observation 是 ReAct 的三要素，
     * 也是调试 Agent 唯一有效的手段。
     */
    public static class AgentStep {
        private int round;
        /** 模型的推理/文本输出。 */
        private String thought;
        /** 调用了哪个工具、参数是什么。 */
        private String action;
        /** 工具返回了什么（瘦身后）。 */
        private String observation;
        private Long elapsedMs;

        public AgentStep() {
        }

        public AgentStep(int round, String thought, String action, String observation, Long elapsedMs) {
            this.round = round;
            this.thought = thought;
            this.action = action;
            this.observation = observation;
            this.elapsedMs = elapsedMs;
        }

        public int getRound() { return round; }
        public void setRound(int round) { this.round = round; }
        public String getThought() { return thought; }
        public void setThought(String thought) { this.thought = thought; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getObservation() { return observation; }
        public void setObservation(String observation) { this.observation = observation; }
        public Long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    }
}
