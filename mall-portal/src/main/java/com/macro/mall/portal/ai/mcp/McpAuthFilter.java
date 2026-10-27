package com.macro.mall.portal.ai.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP 端点的共享密钥鉴权。
 *
 * ============================ 为什么必须有这一层 ============================
 * Spring AI 官方文档对 MCP 的 HTTP 传输写得很直白：
 *
 *   「The HTTP-based server transports expose an **unauthenticated** JSON-RPC
 *    endpoint by default. The starters do not apply any authentication or
 *    authorization on their own, so any client that can reach the endpoint can
 *    list and invoke every registered tool, resource, and prompt.」
 *
 * 也就是说，不自己加鉴权，等于把商城的商品查询能力完全公开给任何能连上端口的人。
 * 虽然我们只暴露了公开的商品数据（不含订单），但「谁能查、查多快」仍然该受控 ——
 * 否则这就是个免费的、可被任意刷的接口，成本全落在自己身上。
 *
 * ============================ 为什么是共享密钥而不是 JWT ============================
 * MCP 的调用方是外部客户端（Claude Desktop、IDE 插件），它们：
 *   - 不走 mall 的 /sso/login 流程，拿不到 JWT
 *   - 是「机器对机器」而非「用户对服务器」，本来就没有用户概念
 * 共享密钥（Bearer-style 的固定 token）是这类场景的标准做法：
 * 简单、无状态、不需要额外的令牌签发与刷新机制。
 *
 * ============================ 为什么是 fail-closed ============================
 * 没配密钥（spring.ai.mcp.auth-token 为空）时，本过滤器**拒绝所有请求**，
 * 而不是「没配就放行」。
 *
 * 理由：默认安全（secure by default）比事后加固可靠。
 * 「忘了配」是必然会发生的，如果忘了配的后果是「端口对全世界敞开」，
 * 那这种配置项本身就是个陷阱。
 *
 * ============================ 实现细节 ============================
 * 1. 用 MessageDigest.isEqual 做**常量时间比较**，避免计时攻击
 *    （普通 equals 会在第一个不同字节处提前返回，攻击者能通过响应时间差逐字节猜出密钥）
 * 2. accept 头检查：MCP 客户端必须声明支持 json 或 event-stream，否则直接 406
 * 3. 只拦 /mcp 路径，不影响商城其它接口
 *
 * Created 2026/10/27.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "STREAMABLE")
public class McpAuthFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpAuthFilter.class);

    /** 请求头名。 */
    public static final String TOKEN_HEADER = "X-MCP-Token";

    /** MCP 端点的路径前缀。 */
    private static final String MCP_PATH = "/mcp";

    /** 期望的密钥。为空则整个端点拒绝服务（fail-closed）。 */
    @Value("${mall.ai.mcp.auth-token:}")
    private String expectedToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦 MCP 端点，商城其它接口完全不受影响
        String uri = request.getRequestURI();
        return uri == null || !(uri.equals(MCP_PATH) || uri.startsWith(MCP_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // ---- ① fail-closed：没配密钥就一律拒绝 ----
        if (expectedToken == null || expectedToken.trim().isEmpty()) {
            LOGGER.warn("[MCP] 未配置 mall.ai.mcp.auth-token，拒绝所有 MCP 请求（fail-closed）");
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "MCP 端点未启用：服务端未配置访问密钥。");
            return;
        }

        // ---- ② 校验请求头 ----
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || provided.isEmpty()) {
            LOGGER.warn("[MCP] 请求缺少 {} 请求头，已拒绝。remote={}", TOKEN_HEADER, request.getRemoteAddr());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "缺少 " + TOKEN_HEADER + " 请求头。");
            return;
        }

        // 常量时间比较 —— 防计时攻击（见类注释）
        if (!constantTimeEquals(provided, expectedToken.trim())) {
            LOGGER.warn("[MCP] 密钥不匹配，已拒绝。remote={}", request.getRemoteAddr());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "访问密钥无效。");
            return;
        }

        // ---- ③ Accept 头检查：MCP 客户端必须声明支持这两种内容类型之一 ----
        String accept = request.getHeader("Accept");
        if (accept == null
                || (!accept.contains("application/json") && !accept.contains("text/event-stream"))) {
            LOGGER.warn("[MCP] Accept 头不合法: {}, 已拒绝", accept);
            writeError(response, HttpServletResponse.SC_NOT_ACCEPTABLE,
                    "Accept 头必须包含 application/json 或 text/event-stream。");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 常量时间字符串比较。
     *
     * 用 MessageDigest.isEqual 而不是 String.equals：
     * equals 会在发现第一个不同字节时立即返回，攻击者可以通过测量响应时间
     * 逐字节"猜"出密钥（计时攻击）。isEqual 保证比较耗时与内容无关。
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // 手动拼 JSON，避免为这一点逻辑引入额外依赖
        response.getWriter().println(
                "{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
