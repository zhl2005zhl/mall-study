package com.macro.mall.common.log;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.domain.WebLog;
import com.macro.mall.common.util.RequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import net.logstash.logback.marker.Markers;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一日志处理切面
 * Created by macro on 2018/4/26.
 */
@Aspect
@Component
@Order(1)
public class WebLogAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebLogAspect.class);

    /**
     * 需要脱敏的字段名。
     *
     * 这个切面的范围覆盖 mall-admin 与 mall-portal 的全部 controller，
     * 其中 /admin/login 的请求体里有明文密码、响应体里有 JWT。
     * 不脱敏就等于把后台凭证写进日志文件，而日志通常会被采集到 ELK、
     * 被运维和开发随意查看，管控级别远低于数据库。
     */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|token|tokenHead|secret|authorization)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /** 非 CommonResult 的返回值，最多记录这么多字符（避免大响应体把日志冲爆） */
    private static final int RESULT_MAX_LENGTH = 200;

    /**
     * 是否记录完整请求参数与响应体。
     * 默认 false —— 只记访问摘要。排查问题时用
     * {@code --logging.weblog.body=true} 临时打开。
     */
    @Value("${logging.weblog.body:false}")
    private boolean logBody;

    @Pointcut("execution(public * com.macro.mall.controller.*.*(..))||execution(public * com.macro.mall.*.controller.*.*(..))")
    public void webLog() {
    }

    @Before("webLog()")
    public void doBefore(JoinPoint joinPoint) throws Throwable {
    }

    @AfterReturning(value = "webLog()", returning = "ret")
    public void doAfterReturning(Object ret) throws Throwable {
    }

    @Around("webLog()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        //获取当前请求对象
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        //记录请求信息(通过Logstash传入Elasticsearch)
        WebLog webLog = new WebLog();
        Object result = joinPoint.proceed();
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        if (method.isAnnotationPresent(Operation.class)) {
            Operation log = method.getAnnotation(Operation.class);
            webLog.setDescription(log.summary());
        }
        long endTime = System.currentTimeMillis();
        String urlStr = request.getRequestURL().toString();
        webLog.setBasePath(StrUtil.removeSuffix(urlStr, URLUtil.url(urlStr).getPath()));
        webLog.setUsername(request.getRemoteUser());
        webLog.setIp(RequestUtil.getRequestIp(request));
        webLog.setMethod(request.getMethod());
        webLog.setParameter(getParameter(method, joinPoint.getArgs()));
        // 响应体默认只记摘要：完整响应体既可能是几 KB 的大对象（实测首页接口约 11KB，
        // 40 并发压测 15 秒能写出 25MB 日志），也可能包含 token 这类敏感字段
        webLog.setResult(logBody ? result : summarizeResult(result));
        webLog.setSpendTime((int) (endTime - startTime));
        webLog.setStartTime(startTime);
        webLog.setUri(request.getRequestURI());
        webLog.setUrl(request.getRequestURL().toString());
        Map<String,Object> logMap = new HashMap<>();
        logMap.put("url",webLog.getUrl());
        logMap.put("method",webLog.getMethod());
        logMap.put("spendTime",webLog.getSpendTime());
        logMap.put("description",webLog.getDescription());
        logMap.put("parameter", webLog.getParameter() == null
                ? null : maskSensitive(JSONUtil.parse(webLog.getParameter()).toString()));
        // INFO 行保留「访问日志」的价值：谁、什么方法、哪个接口、耗时多少 —— 这些都不敏感
        LOGGER.info(Markers.appendEntries(logMap), "{} {} {}ms",
                webLog.getMethod(), webLog.getUri(), webLog.getSpendTime());
        // 完整内容（含请求参数）只在 DEBUG 输出，并且敏感字段已脱敏
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(Markers.appendEntries(logMap), maskSensitive(JSONUtil.parse(webLog).toString()));
        }
        return result;
    }

    /**
     * 把 JSON 里敏感字段的值替换成 ***。
     * 用正则整串替换而不是逐个字段判断，是因为请求体是任意对象，
     * 敏感字段可能嵌套在好几层里，逐个判断很容易漏。
     */
    private String maskSensitive(String json) {
        if (StrUtil.isEmpty(json)) {
            return json;
        }
        return SENSITIVE_PATTERN.matcher(json).replaceAll("$1\"***\"");
    }

    /**
     * 只保留响应的「结论」：CommonResult 取 code 与 message，
     * 其它类型截断到固定长度。正文排查用不着完整响应。
     */
    private Object summarizeResult(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof CommonResult<?> commonResult) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("code", commonResult.getCode());
            summary.put("message", commonResult.getMessage());
            return summary;
        }
        String text = String.valueOf(result);
        return text.length() > RESULT_MAX_LENGTH
                ? text.substring(0, RESULT_MAX_LENGTH) + "…(truncated)"
                : text;
    }

    /**
     * 根据方法和传入的参数获取请求参数
     */
    private Object getParameter(Method method, Object[] args) {
        List<Object> argList = new ArrayList<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            //将RequestBody注解修饰的参数作为请求参数
            RequestBody requestBody = parameters[i].getAnnotation(RequestBody.class);
            if (requestBody != null) {
                argList.add(args[i]);
            }
            //将RequestParam注解修饰的参数作为请求参数
            RequestParam requestParam = parameters[i].getAnnotation(RequestParam.class);
            if (requestParam != null) {
                Map<String, Object> map = new HashMap<>();
                String key = parameters[i].getName();
                if (!StrUtil.isEmpty(requestParam.value())) {
                    key = requestParam.value();
                }
                if(args[i]!=null){
                    map.put(key, args[i]);
                    argList.add(map);
                }
            }
        }
        if (argList.size() == 0) {
            return null;
        } else if (argList.size() == 1) {
            return argList.get(0);
        } else {
            return argList;
        }
    }
}
