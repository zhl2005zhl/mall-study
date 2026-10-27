package com.macro.mall.portal.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.ai.domain.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Agent trace 落盘器：每次执行写一行 JSON（JSONL 格式）。
 *
 * ============================ 为什么是 JSONL（一行一条） ============================
 * 选它而不是「一个请求一个文件」或「塞进普通日志」：
 *
 *   vs 普通日志：普通日志是给人读的，要分析「平均多少轮」「哪个工具最慢」
 *                就得写正则去扒，脆且累。JSONL 可以直接 jq / pandas 读。
 *   vs 一请求一文件：文件数量爆炸，grep 还得跨文件。
 *
 * JSONL 的好处是一条记录一行，追加写、不破坏已有内容，
 * 又能直接被结构化工具消费：
 *   jq -s 'map(.totalTokens) | add' agent-trace-2026-10-27.jsonl
 *
 * ============================ 为什么要落盘而不是只打日志 ============================
 * 这个项目的应用跑在后台、日志会被回收（AGENTS.md 里记过这个坑）。
 * trace 是要**拿来分析和展示**的，不该跟着日志一起滚掉。
 * 单独落一个按天分文件的 trace，既能长期留存，也方便随时翻出来看。
 *
 * ★ 隐私提醒：trace 里包含用户问题和订单信息。
 *   这属于生产数据，只适合本地演示，真实环境要脱敏或受限保存。
 *   这里按天分文件、放在 logs 目录下，不进版本库。
 *
 * Created 2026/10/27.
 */
@Component
public class AgentTraceRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentTraceRecorder.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    public AgentTraceRecorder(ObjectMapper objectMapper, AgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 写入一条 trace。失败绝不影响主流程 —— trace 是观测手段，不是业务依赖。
     * 这条原则很重要：不能因为「日志写不进去」就让用户的请求失败。
     */
    public void record(AgentTrace trace) {
        if (!properties.isTraceEnabled()) {
            return;
        }
        if (trace == null) {
            return;
        }
        try {
            String dir = properties.getTraceDir();
            Path dirPath = Paths.get(dir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }
            String fileName = "agent-trace-" + LocalDate.now().format(DAY) + ".jsonl";
            Path file = dirPath.resolve(fileName);

            String line = objectMapper.writeValueAsString(trace) + System.lineSeparator();
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // 只告警，绝不冒泡 —— 观测失败不该拖垮业务
            LOGGER.warn("[AI Agent] trace 落盘失败（不影响本次请求）", e);
        }
    }
}
