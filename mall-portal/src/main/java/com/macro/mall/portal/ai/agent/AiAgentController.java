package com.macro.mall.portal.ai.agent;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.portal.ai.domain.AgentTrace;
import com.macro.mall.portal.ai.rag.KnowledgeRetriever;
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
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
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

            ## ★ 数据时效性（重要）
            订单的状态、金额、物流都会随时间变化。你上一轮查到的数据**可能已经过时**。
            因此：
            - 当用户问的是「现在」的情况（如"多少钱""什么状态""到哪了""还有货吗"），
              **必须重新调用工具确认**，不要直接复述前面几轮的查询结果。
            - 只有在用户明确是在指代历史结论（如"刚才那个订单号是多少"）时，
              才可以引用上文里已经得到的**静态信息**（订单号、下单时间、商品名）。
            - 拿不准数据是否还准确时，宁可多查一次，也不要给出可能过时的数字。

            ## 回答风格
            - 用中文，简洁，像真人客服，不要输出 JSON 或 Markdown 表格。
            - 涉及金额要写清币种（元）。
            - 回答完可以补一句下一步建议，但不要啰嗦。
            """;

    private final ChatModel chatModel;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;
    private final ObjectProvider<com.macro.mall.portal.ai.tool.MallTools> mallToolsProvider;
    private final AgentProperties agentProperties;
    /** trace 落盘（JSONL）。写失败不影响请求。 */
    private final AgentTraceRecorder traceRecorder;
    /**
     * RAG 知识检索器。RAG 未启用时为 null（用 ObjectProvider 拿，允许缺失）。
     * 为 null 时走「无知识库」路径，不影响工具调用能力。
     */
    private final com.macro.mall.portal.ai.rag.KnowledgeRetriever knowledgeRetriever;
    /** 当前使用的模型名，用于 trace 里横向对比成本。 */
    private final String modelName;

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
     *
     * ============================ 关键：必须传递 SecurityContext ============================
     * 踩过的坑（真机验证暴露的，不接真模型根本发现不了）：
     *   SecurityContextHolder 默认用 ThreadLocal 存储，**不会跨线程传递**。
     *   而 Agent 循环跑在上述线程池里，工具内部 memberService.getCurrentMember()
     *   取到的是 null，直接抛：
     *     NullPointerException: Cannot invoke "Authentication.getPrincipal()" because "auth" is null
     *
     *   更隐蔽的是它的**表现形式**：工具抛异常被兜住 → 回填"内部错误"给模型 →
     *   模型转头对用户说"系统出了点内部错误，您稍后再试"。
     *   接口返回 200，回答听起来很自然，**看起来像正常的服务降级**，
     *   完全看不出是权限上下文丢了。
     *
     * 修法：用 DelegatingSecurityContextExecutorService 包装，
     * 让任务在提交时捕获当前线程的 SecurityContext，在执行线程里还原。
     * 这样既保留了超时中断能力，又不丢身份 —— 工具层的安全设计才真正生效。
     */
    private final ExecutorService executor = new DelegatingSecurityContextExecutorService(
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mall-ai-agent");
                t.setDaemon(true);
                return t;
            }));

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
                             AgentProperties agentProperties,
                             AgentTraceRecorder traceRecorder,
                             ObjectProvider<com.macro.mall.portal.ai.rag.KnowledgeRetriever> retrieverProvider,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${spring.ai.deepseek.chat.options.model:deepseek-chat}") String modelName) {
        this.chatModel = chatModel;
        this.toolCallbackProviders = toolCallbackProviders;
        this.mallToolsProvider = mallToolsProvider;
        this.agentProperties = agentProperties;
        this.traceRecorder = traceRecorder;
        this.modelName = modelName;
        // 用 ObjectProvider 而不是直接注入：RAG 未启用（没配 Milvus/Ollama）时
        // KnowledgeRetriever 这个 Bean 根本不存在，直接注入会导致启动失败。
        // 用 Provider 拿到 null，走"无知识库"路径 —— RAG 是增强能力，不该成为硬依赖。
        this.knowledgeRetriever = retrieverProvider.getIfAvailable();
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

        // ---- ★ RAG 检索：先查资料，再让模型带着资料回答 ----
        //
        // 顺序很重要：**检索发生在第一轮模型调用之前**，而不是让模型"决定要不要查知识库"。
        // 因为知识库问答是「无状态的一次性检索」，没有多轮价值：
        //   - 用户问「能退货吗」→ 检索一次政策就够，不需要模型自己规划
        //   - 让模型调工具去查，反而多一轮开销，还容易漏查
        // 这跟订单查询不同（那个需要按用户意图分步：先列订单、再查详情）。
        //
        // 检索不到时 knowledgeContext 为空，模型会走"没有知识库"的路径，
        // 而不是拿到一堆无关内容硬编 —— 这就是 similarityThreshold 的意义。
        String knowledgeContext = "";
        List<com.macro.mall.portal.ai.rag.KnowledgeRetriever.RetrievedChunk> chunks = new ArrayList<>();
        if (knowledgeRetriever != null && !isDataQuery(question)) {
            chunks = knowledgeRetriever.retrieve(question);
            knowledgeContext = knowledgeRetriever.buildContext(chunks);
        }

        // ---- 组装历史（带滑动窗口）----
        List<Message> history = new ArrayList<>(
                sessionHistory.getOrDefault(sessionId, new ArrayList<>()));
        history.add(new UserMessage(question));

        ChatClient client = this.chatClient;

        // ---- trace：每轮记录 thought / action / observation + token ----
        List<AgentTrace.Step> traceSteps = new ArrayList<>();
        // 重复调用检测：记录「工具名 + 参数」指纹
        Set<String> seenCalls = new HashSet<>();
        // 历史裁剪次数 —— 用来观察上下文压力
        int historyTrimCount = 0;
        // 记录本次用的模型名，便于横向对比成本
        String startModel = resolveModelName();

        String finalAnswer = null;
        String stopReason = null;
        int round = 0;

        for (round = 1; round <= agentProperties.getMaxRounds(); round++) {
            long roundStart = System.currentTimeMillis();

            // ① 调模型（带上完整的工具清单和历史）
            //
            // ★★ 这里有一个**决定「手写」还是「框架代劳」的关键开关** ★★
            //
            // Spring AI 的 ChatModel 默认会**自己**执行工具调用：
            //   调模型 → 拿到 tool_calls → 自己执行 → 把结果回填 → 再调模型 → …
            // 直到模型给出最终答案，才把结果返回给你。
            //
            // 后果是：我们拿到的 assistantMessage 里 getToolCalls() 永远是空的，
            // 循环里的「执行工具 / 回填 / 重复检测 / 轮次统计」全部不会执行 ——
            // 表面上 Answer 看起来挺正常，实际是框架在背后跑完了一切。
            // 这正是设计文档要避免的：「框架把循环封在里面，出问题时是黑盒」。
            //
            // 所以必须显式关掉它（internalToolExecutionEnabled=false）：
            // 模型只负责「说要调什么」，执行权完全留在我们手里。
            // 顺带的好处是，SecurityContext、超时中断、trace 记录都在我们掌控下。
            DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                    .internalToolExecutionEnabled(false)
                    .build();

            // system prompt 拼上 RAG 检索到的知识（如果有）。
            // 为什么放 system 而不是塞进 user 消息：
            //   system 是"角色设定 + 参考资料"的正确位置，模型会把它当权威背景；
            //   塞进 user 消息会和用户的实际提问混在一起，模型容易分不清
            //   「这是用户说的」还是「这是系统给的资料」。
            String systemWithKb = knowledgeContext.isEmpty()
                    ? SYSTEM_PROMPT
                    : SYSTEM_PROMPT + "\n\n" + knowledgeContext;

            ChatResponse response = client.prompt()
                    .system(systemWithKb)
                    .messages(history)
                    .toolCallbacks(tools)
                    .options(options)
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null) {
                stopReason = "模型返回为空";
                break;
            }

            Generation generation = response.getResult();
            AssistantMessage assistantMessage = generation.getOutput();
            history.add(assistantMessage);

            // ★ token 计量：从响应元数据里取，取不到就当 0（EmptyUsage 会返回 0）
            //   这是「量化成本」的唯一来源 —— 没有它，谈优化都是空话。
            int roundPromptTokens = usagePrompt(response);
            int roundCompletionTokens = usageCompletion(response);

            // ② 判断：模型是要调工具，还是给最终答案
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                finalAnswer = assistantMessage.getText();
                AgentTrace.Step step = new AgentTrace.Step();
                step.setRound(round);
                step.setThought(truncate(assistantMessage.getText()));
                step.setElapsedMs(System.currentTimeMillis() - roundStart);
                step.setPromptTokens(roundPromptTokens);
                step.setCompletionTokens(roundCompletionTokens);
                traceSteps.add(step);
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

                // ★ 回填前做**字符级截断**：单条工具结果过长时，即便字段已经瘦身，
                //   也可能因为条数多而变长（比如 10 个订单 × 每个几百字符）。
                //   截断而不是丢弃：保留前面有用的部分，并在末尾注明被截断，
                //   让模型知道"不是没有更多数据，而是显示上限到了"，
                //   而不是以为自己看到的就是全部。
                int rawLen = observation == null ? 0 : observation.length();
                String shrunk = shrinkObservation(observation);

                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), shrunk));

                AgentTrace.Step step = new AgentTrace.Step();
                step.setRound(round);
                step.setAction(call.name() + "(" + truncate(call.arguments()) + ")");
                step.setObservation(truncate(shrunk));
                step.setObservationChars(rawLen);
                step.setElapsedMs(System.currentTimeMillis() - roundStart);
                step.setPromptTokens(roundPromptTokens);
                step.setCompletionTokens(roundCompletionTokens);
                traceSteps.add(step);
            }
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());

            // ④ 历史裁剪：条数 + 字符数双重限制
            if (trimHistory(history)) {
                historyTrimCount++;
            }
        }

        if (finalAnswer == null && stopReason == null) {
            // 跑满轮次还没给答案 —— 这是「最大轮次」兜底生效
            stopReason = "达到最大轮次上限（" + agentProperties.getMaxRounds() + "），强制终止";
            finalAnswer = "这个问题的处理步骤有点多，我暂时没能查完。"
                    + "可以试着把问题说得更具体一些（比如直接给订单号），我再帮你查一次。";
        }

        // ---- 保存历史（裁剪后）----
        if (trimHistory(history)) {
            historyTrimCount++;
        }
        sessionHistory.put(sessionId, history);

        // ---- 汇总 trace ----
        long costMs = System.currentTimeMillis() - startMs;
        int promptTotal = traceSteps.stream().mapToInt(AgentTrace.Step::getPromptTokens).sum();
        int completionTotal = traceSteps.stream().mapToInt(AgentTrace.Step::getCompletionTokens).sum();

        AgentTrace trace = new AgentTrace();
        trace.setTraceId(java.util.UUID.randomUUID().toString().substring(0, 8));
        trace.setSessionId(sessionId);
        trace.setQuestion(question);
        trace.setAnswer(finalAnswer);
        trace.setSteps(traceSteps);
        trace.setRounds(round);
        trace.setToolCallCount(traceSteps.stream().filter(s -> s.getAction() != null).count());
        trace.setPromptTokens(promptTotal);
        trace.setCompletionTokens(completionTotal);
        trace.setTotalTokens(promptTotal + completionTotal);
        trace.setCostMs(costMs);
        trace.setStopReason(stopReason);
        trace.setSuccess(finalAnswer != null);
        trace.setHistoryTrimCount(historyTrimCount);
        trace.setModel(startModel);
        // 记录 RAG 检索情况：命中几块、命中了哪些标题。
        // 这是评估 RAG 效果最直接的依据 —— 回答不准时要先分清是
        // 「没检索到」还是「检索到了但模型没用对」，两者的修法完全不同。
        trace.setRagHitCount(chunks.size());
        trace.setRagTitles(chunks.stream()
                .map(KnowledgeRetriever.RetrievedChunk::getTitle)
                .reduce((a, b) -> a + " | " + b).orElse(""));

        // 落盘（失败不影响请求）
        traceRecorder.record(trace);

        AiChatResult result = new AiChatResult();
        result.setAnswer(finalAnswer);
        result.setSessionId(sessionId);
        result.setRounds(round);
        result.setSteps(trace.getSteps());
        result.setStopReason(stopReason);
        result.setCostMs(costMs);
        result.setToolCallCount(trace.getToolCallCount());
        // 成本与裁剪信息一并返回，便于前端/演示时展示
        result.setTotalTokens(trace.getTotalTokens());
        result.setPromptTokens(trace.getPromptTokens());
        result.setCompletionTokens(trace.getCompletionTokens());
        result.setHistoryTrimCount(historyTrimCount);
        result.setTraceId(trace.getTraceId());
        // 把 RAG 命中情况一并返回：调用方不必翻 trace 文件就能判断检索质量
        result.setRagHitCount(trace.getRagHitCount());
        result.setRagTitles(trace.getRagTitles());

        LOGGER.info("[AI Agent] 完成 traceId={} sessionId={} 轮次={} 工具调用={} "
                        + "token={}(prompt={}/completion={}) 耗时={}ms 裁剪={}次 终止原因={}",
                trace.getTraceId(), sessionId, round, result.getToolCallCount(),
                trace.getTotalTokens(), promptTotal, completionTotal,
                costMs, historyTrimCount, stopReason);
        return result;
    }

    /**
     * 判断这是不是「查我的数据」类问题（订单/购物车/地址等个人数据）。
     *
     * ============================ 为什么需要这个判断 ============================
     * 真机验证踩到的坑，而且很反直觉：
     *
     *   「我有几个订单？」  →  与知识块「订单取消」相似度 **0.61**（> 阈值 0.5，误命中）
     *   「优惠券能叠加吗？」→  与订单政策相似度   **0.47**（< 阈值，正确不命中）
     *
     * 也就是说：**问「我的订单」比问「优惠券政策」更容易命中订单类知识块**。
     * 根因是「订单」这个词的语义锚定太强 —— 两个句子都含「订单」，
     * 向量相似度天然偏高。这是 RAG 的经典陷阱：**词面相似 ≠ 意图相关**。
     *
     * 光调阈值解决不了：要滤掉 0.61 就得把阈值提到 0.62，
     * 但那会连带把真正相关的政策问答也挡掉。
     *
     * 所以改成**意图路由**：查个人数据的走工具，查政策规则的才走知识库。
     * 这也符合业务直觉 —— 用户问「我有几个订单」时，他要的是自己的订单列表，
     * 不是「订单取消政策」。
     *
     * 用关键词而非模型判断：这里要的是**确定性**和**零成本**。
     * 多调一次模型来做意图分类，既增加延迟又引入不确定性；
     * 而「我的/我有/几笔/多少钱」这类词很稳定，误判代价也小
     * （最坏情况是该查政策时没查，模型会直说不知道，不会编）。
     */
    private boolean isDataQuery(String question) {
        if (question == null || question.isEmpty()) {
            return false;
        }
        String q = question.trim();
        // 第一人称 + 数据量词：几乎必然是在问「我的数据」
        String[] dataPatterns = {
                "我的订单", "我有", "我买", "我的购物车", "我的地址", "我的收藏",
                "几个订单", "几笔订单", "多少订单", "订单状态", "订单到哪", "物流",
                "买了什么", "花了多少", "最近买", "查订单", "查一下订单", "订单号"
        };
        for (String p : dataPatterns) {
            if (q.contains(p)) {
                return true;
            }
        }
        // 18 位纯数字：用户直接贴订单号，也是在查数据
        return q.matches(".*\\b\\d{18}\\b.*");
    }
    /** 当前使用的模型名（构造时注入，trace 里用来对比成本）。 */
    private String resolveModelName() {
        return modelName;
    }
    /** 从响应中读 prompt token，取不到返回 0。 */
    private int usagePrompt(ChatResponse response) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null
                    && response.getMetadata().getUsage().getPromptTokens() != null) {
                return response.getMetadata().getUsage().getPromptTokens();
            }
        } catch (RuntimeException ignored) {
            // 某些实现可能没有 usage，忽略
        }
        return 0;
    }

    /** 从响应中读 completion token，取不到返回 0。 */
    private int usageCompletion(ChatResponse response) {
        try {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null
                    && response.getMetadata().getUsage().getCompletionTokens() != null) {
                return response.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (RuntimeException ignored) {
            // 同上
        }
        return 0;
    }

    /**
     * 回填前的字符级截断。
     *
     * 与「字段瘦身」的分工：
     *   字段瘦身（OrderSummary 等）解决的是「单条数据太宽」——30 个字段砍到 4 个。
     *   这里解决的是「整体太长」——10 个订单 × 每个 300 字符 = 3000 字符，仍然可能过大。
     *
     * 为什么截断而不是直接丢弃：丢掉整条会让模型以为「没有数据」，
     * 而截断 + 明确注明，模型能知道「数据还有，但被显示上限截住了」——
     * 它可以据此决定「只查其中一条」而不是「报告查不到」。
     */
    private String shrinkObservation(String observation) {
        if (observation == null) {
            return "";
        }
        int max = agentProperties.getMaxObservationChars();
        if (observation.length() <= max) {
            return observation;
        }
        return observation.substring(0, max)
                + "…（结果过长，此处截断，共 " + observation.length() + " 字符。"
                + "如需完整信息，请缩小查询范围，例如指定单个订单号）";
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
     * 历史裁剪：**条数 + 字符数**双重限制。
     *
     * ============================ 为什么两个限制都要有 ============================
     * 只按条数（原来的实现）有个致命盲区：一条 tool 响应可能几万字符。
     * 「20 条消息」听起来很少，但如果其中 10 条是工具结果，照样能到几十万字符、
     * 直接超出上下文窗口。**真正的成本瓶颈是字符/token 数，不是条数。**
     *
     * 所以两种限制分工明确：
     *   条数 → 防止「碎消息」堆积（很多轮短对话）
     *   字符 → 防止「单条巨无霸」（一次返回大批数据）
     *
     * ============================ 为什么整段丢，不逐条丢 ============================
     * assistant(工具调用) 和 tool(工具结果) 是**成对**的。
     * 砍掉 assistant 却留下 tool 响应，多数模型会直接 400（消息顺序不合法）。
     * 所以每次都丢到「下一个 UserMessage 开头」为止，保证成对关系完整。
     *
     * @return 是否真的发生了裁剪（用于统计上下文压力）
     */
    private boolean trimHistory(List<Message> history) {
        int maxMessages = agentProperties.getMaxHistoryMessages();
        int maxChars = agentProperties.getMaxHistoryChars();
        boolean trimmed = false;

        while (needTrim(history, maxMessages, maxChars)) {
            int cut = -1;
            for (int i = 1; i < history.size(); i++) {
                if (history.get(i) instanceof UserMessage) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) {
                // 找不到安全的截断点：说明只剩「一条用户消息 + 一堆工具往返」，
                // 再丢就会破坏成对关系。保留第一条，让它自然结束。
                break;
            }
            history.subList(0, cut).clear();
            trimmed = true;
        }
        return trimmed;
    }

    /** 判断是否需要裁剪。 */
    private boolean needTrim(List<Message> history, int maxMessages, int maxChars) {
        if (history.size() > maxMessages) {
            return true;
        }
        long chars = 0;
        for (Message m : history) {
            chars += messageLength(m);
        }
        return chars > maxChars;
    }

    /**
     * 估算一条消息的字符数。
     *
     * 为什么要估算而不是精确统计 token：token 数要模型侧才准，
     * 本地只能拿字符数做近似（中文约 1 字符 ≈ 1 token，英文约 4 字符 ≈ 1 token）。
     * 作为**上限保护**，字符数这个粗粒度信号完全够用 —— 目标是「别爆」，不是「精确计费」。
     */
    private int messageLength(Message m) {
        if (m == null) {
            return 0;
        }
        try {
            String text = m.getText();
            int len = text == null ? 0 : text.length();
            // ToolResponseMessage 的正文在 responses 里，getText() 取不到，
            // 这里按 responses 的数量做个保守估算
            if (m instanceof ToolResponseMessage) {
                ToolResponseMessage trm = (ToolResponseMessage) m;
                if (trm.getResponses() != null) {
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        if (r.responseData() != null) {
                            len += r.responseData().length();
                        }
                    }
                }
            }
            return len;
        } catch (RuntimeException e) {
            return 0;
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
        private List<AgentTrace.Step> steps;
        /** 终止原因：正常结束 / 达到最大轮次 / 前置条件不满足。 */
        private String stopReason;
        /** 总耗时（毫秒）。 */
        private Long costMs;
        /** 工具调用总次数。 */
        private Long toolCallCount;

        /** 本次消耗的总 token（prompt + completion）。 */
        private Integer totalTokens;
        private Integer promptTokens;
        private Integer completionTokens;
        /** 历史被裁剪的次数，用于观察上下文压力。 */
        private Integer historyTrimCount;
        /** trace id，可与落盘的 JSONL 对应。 */
        private String traceId;
        /** RAG 检索命中的知识块数量（RAG 未启用或未命中时为 0）。 */
        private Integer ragHitCount;
        /** 命中的知识块标题，便于核查「检索得对不对」。 */
        private String ragTitles;

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public int getRounds() { return rounds; }
        public void setRounds(int rounds) { this.rounds = rounds; }
        public List<AgentTrace.Step> getSteps() { return steps; }
        public void setSteps(List<AgentTrace.Step> steps) { this.steps = steps; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
        public Long getCostMs() { return costMs; }
        public void setCostMs(Long costMs) { this.costMs = costMs; }
        public Long getToolCallCount() { return toolCallCount; }
        public void setToolCallCount(Long toolCallCount) { this.toolCallCount = toolCallCount; }
        public Integer getTotalTokens() { return totalTokens; }
        public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
        public Integer getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
        public Integer getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
        public Integer getHistoryTrimCount() { return historyTrimCount; }
        public void setHistoryTrimCount(Integer historyTrimCount) { this.historyTrimCount = historyTrimCount; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public Integer getRagHitCount() { return ragHitCount; }
        public void setRagHitCount(Integer ragHitCount) { this.ragHitCount = ragHitCount; }
        public String getRagTitles() { return ragTitles; }
        public void setRagTitles(String ragTitles) { this.ragTitles = ragTitles; }
    }

    // 单轮记录统一使用 AgentTrace.Step（见 ai/domain/AgentTrace.java），
    // 不再在这里维护一份重复结构 —— 两份结构迟早会漂移。
}
