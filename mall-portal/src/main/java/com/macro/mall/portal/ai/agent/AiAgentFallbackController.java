package com.macro.mall.portal.ai.agent;

import com.macro.mall.common.api.CommonResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 未配置模型时的降级控制器。
 *
 * ============================ 为什么需要它 ============================
 * 实测踩到两个坑，这个类就是用来同时兜住它们的：
 *
 * 坑 1：没配 Key 时 DeepSeekChatAutoConfiguration 会抛
 *       "DeepSeek API key must be set"，直接让 mall-portal 启动失败，
 *       连原有的商城业务都跑不了。→ 用 spring.ai.model.chat=none 关掉该自动配置。
 *
 * 坑 2：关掉 ChatModel 之后，ChatClientAutoConfiguration 仍会尝试创建
 *       ChatClient.Builder，而它依赖 ChatModel → 请求进来时抛
 *       NoSuchBeanDefinitionException，被全局异常处理成 **401**。
 *       症状极具误导性：日志里能看到 "checking username:test" 却看不到
 *       "authenticated user"，看起来像鉴权失败，其实跟鉴权毫无关系。
 *       → 在 application.yml 里用 spring.autoconfigure.exclude 把它排除掉。
 *
 * 最终方案：
 *   - AiAgentController      用 @ConditionalOnProperty(chat=deepseek)：配了才装配
 *   - 本类                    同一个属性取反（havingValue=none）：没配时接管 /ai/**
 *   - 两者互斥，且都只影响 /ai/**，绝不影响其它业务接口
 *
 * 这样「没配 Key」的表现是**可读的提示**，而不是启动失败或莫名其妙的 401。
 *
 * Created 2026/10/20.
 */
@RestController
@RequestMapping("/ai")
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "none", matchIfMissing = true)
public class AiAgentFallbackController {

    private static final String TIP =
            "AI 模型未配置，智能客服暂不可用（其它商城功能不受影响）。"
            + "启用方式：设置环境变量 DEEPSEEK_API_KEY 和 AI_CHAT_PROVIDER=deepseek 后重启服务。"
            + "PowerShell 示例：$env:DEEPSEEK_API_KEY=\"sk-你的Key\"; $env:AI_CHAT_PROVIDER=\"deepseek\"";

    @PostMapping("/chat")
    public CommonResult<String> chat(@RequestBody(required = false) AiAgentController.AiChatRequest request) {
        return CommonResult.failed(TIP);
    }

    @PostMapping("/chat/reset")
    public CommonResult<String> reset(@RequestBody(required = false) AiAgentController.AiChatRequest request) {
        return CommonResult.success("AI 模型未配置，无会话可清空");
    }
}
