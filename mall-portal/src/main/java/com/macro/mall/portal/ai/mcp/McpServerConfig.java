package com.macro.mall.portal.ai.mcp;

import com.macro.mall.portal.ai.agent.AgentProperties;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MCP Server 配置：把商品类工具通过 MCP 协议暴露出去。
 *
 * ============================ 这一步在做什么 ============================
 * 前面三步做出的是一个「自家 App 内部能用」的 Agent。
 * MCP 的价值在于**把能力标准化**：用统一的 JSON-RPC 协议暴露工具，
 * 任何支持 MCP 的客户端（Claude Desktop、各类 IDE 插件、其他 Agent 框架）
 * 都能直接调用，而不用为每个客户端写一套适配。
 *
 * 一句话对比：
 *   没有 MCP：能力绑死在自己的应用里，只有自己的前端能用
 *   有了 MCP：这个商城的能力变成「任何 AI 客户端都能接」的标准件
 *
 * ============================ 零重复代码的实现方式 ============================
 * 这里没有重新写一遍工具定义，而是：
 *   1. McpProductTools 上用 @Tool 标注（和内部 Agent 完全相同的写法）
 *   2. ToolCallbacks.from(...) 把它转成 ToolCallback
 *   3. McpToolUtils.toSyncToolSpecification(...) 再转成 MCP 规格
 *
 * 也就是说：**工具只定义一次，既能给 Agent Loop 用，也能给 MCP 客户端用。**
 * 变的是传输协议，不是业务逻辑。
 *
 * ============================ 安全边界（本步骤最关键的点） ============================
 * 官方文档明确警告：MCP 的 HTTP 端点**默认没有任何鉴权**，
 * 任何能访问端点的人都能列出并调用全部已注册的工具。
 *
 * 所以我们做了两道限制：
 *
 * ① 只暴露商品工具（McpProductTools），不暴露订单工具（MallTools）。
 *    因为订单工具靠 SecurityContext 判断「这是谁的数据」，
 *    而 MCP 的外部调用没有这个上下文。若强行暴露，要么工具失效，
 *    要么必须接受调用方传入的 memberId —— 那就等于把身份交给对方指定，
 *    正是我们前面花大力气堵上的水平越权。
 *
 * ② 共享密钥（spring.ai.mcp.auth-token）。
 *    由 McpAuthFilter 在 HTTP 层校验 `X-MCP-Token` 请求头。
 *    没配密钥时**整个 MCP Server 不启用**（fail-closed），
 *    而不是「默认开放」——默认安全比事后加固重要。
 *
 * ============================ 为什么用 STREAMABLE 而不是 SSE ============================
 * SSE 传输在 Spring AI 2.0 已被标记 deprecated，STREAMABLE 是它的替代者：
 * 同样基于 HTTP，但支持 POST + 可选 SSE 流，更适合无状态部署。
 * 这里选 STREAMABLE 是为了不写一个「上线即过时」的东西。
 *
 * Created 2026/10/27.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "STREAMABLE")
public class McpServerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerConfig.class);

    private final McpProductTools productTools;
    private final AgentProperties agentProperties;

    public McpServerConfig(McpProductTools productTools, AgentProperties agentProperties) {
        this.productTools = productTools;
        this.agentProperties = agentProperties;
    }

    /**
     * 注册给 MCP Server 的工具规格。
     *
     * McpServerAutoConfiguration 通过 ObjectProvider<List<SyncToolSpecification>>
     * 收集所有工具规格，所以只要把这个 Bean 暴露出来，工具就自动挂上了。
     */
    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpToolSpecifications() {
        ToolCallback[] callbacks = ToolCallbacks.from(productTools);

        List<ToolCallback> callbackList = new ArrayList<>(Arrays.asList(callbacks));
        List<McpServerFeatures.SyncToolSpecification> specs =
                org.springframework.ai.mcp.McpToolUtils.toSyncToolSpecification(callbackList);

        LOGGER.info("[MCP] 已注册 {} 个工具: {}", specs.size(),
                callbackList.stream()
                        .map(c -> c.getToolDefinition().name())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("无"));
        LOGGER.info("[MCP] 传输协议 STREAMABLE，端点默认 /mcp（需 X-MCP-Token 请求头）");
        LOGGER.info("[MCP] 安全边界：仅暴露商品类只读工具，订单类工具不通过 MCP 开放");
        return specs;
    }
}
