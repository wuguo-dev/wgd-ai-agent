package com.wgd.wgdaiagent.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.graph.agent.node.AgentLlmNode;
import com.wgd.wgdaiagent.tool.AmapMcpToolProvider;
import com.wgd.wgdaiagent.tool.LoggingToolCallback;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.provider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC5（规格 §7.2 / §8.4）：没有 {@code AMAP_MCP_KEY} 时，MCP 相关 WARN <b>恰好一条</b>。
 *
 * <p>降级 WARN 由 {@link AmapMcpToolProvider} 打；{@link LoveAgent} 构造期只留 DEBUG，
 * 避免同一次启动出现两条重复告警。用 logback {@code ListAppender} 直接数日志行。</p>
 */
@Tag("offline")
class LoveAgentStartupLogTest {

    @Test
    void missingMcpKeyLogsExactlyOneMcpWarning() {
        AgentProperties properties = LoveAgentLoopTest.inMemoryProperties();
        // key 为空 => MCP 不建连接，仅本地工具（离线可跑，不会联网）
        AmapMcpToolProvider provider = new AmapMcpToolProvider(properties);

        Logger agentLogger = (Logger) LoggerFactory.getLogger(LoveAgent.class);
        Logger providerLogger = (Logger) LoggerFactory.getLogger(AmapMcpToolProvider.class);
        ListAppender<ILoggingEvent> agentLogs = attach(agentLogger);
        ListAppender<ILoggingEvent> providerLogs = attach(providerLogger);

        try {
            LoveAgentLoopTest.ScriptedChatModel chatModel =
                    new LoveAgentLoopTest.ScriptedChatModel(prompt -> text("收到"));
            new LoveAgent(chatModel, properties, new PromptLoader(), provider,
                    new LoveLocalTools(provider(null)));
        } finally {
            agentLogger.detachAppender(agentLogs);
            providerLogger.detachAppender(providerLogs);
        }

        List<String> mcpWarnings = new ArrayList<>();
        for (ListAppender<ILoggingEvent> appender : List.of(providerLogs, agentLogs)) {
            for (ILoggingEvent event : appender.list) {
                if (event.getLevel() == Level.WARN && event.getFormattedMessage().contains("MCP")) {
                    mcpWarnings.add(event.getFormattedMessage());
                }
            }
        }

        assertEquals(1, mcpWarnings.size(), "AC5：无 key 启动时 MCP 相关 WARN 必须恰好 1 条，实际：" + mcpWarnings);
        assertEquals(true, mcpWarnings.get(0).contains("未启用"), "应保留 provider 的那条降级原因：" + mcpWarnings);
    }

    /**
     * §7.4：框架推理日志（system prompt / 工具入参 / 答复全文）必须默认关闭，可显式打开。
     *
     * <p>用行为断言而不是反射：挂在框架 logger 上数 INFO 行——默认 0 条；显式开启后能看到答复正文。</p>
     */
    @Test
    void reasoningLogsAreOffByDefaultAndCanBeEnabledExplicitly() {
        String answer = "现在是2026年10月24日星期六（隐私正文）";
        Logger reasoningLogger = (Logger) LoggerFactory.getLogger(AgentLlmNode.class);

        assertFalse(new AgentProperties().isLogReasoning(), "logReasoning 默认必须是 false");

        List<ILoggingEvent> defaultLogs = captureInfos(reasoningLogger,
                () -> newAgent(false, answer).chat("chat-default", "现在几点了？"));
        assertTrue(defaultLogs.isEmpty(),
                "默认不得在 INFO 打印 system prompt / 工具入参 / 答复全文，实际：" + messages(defaultLogs));

        List<ILoggingEvent> enabledLogs = captureInfos(reasoningLogger,
                () -> newAgent(true, answer).chat("chat-enabled", "现在几点了？"));
        assertFalse(enabledLogs.isEmpty(), "显式开启后应能看到框架推理日志（排障用）");
        assertTrue(messages(enabledLogs).stream().anyMatch(message -> message.contains("隐私正文")),
                "开启后框架确实会把答复正文打进 INFO —— 这正是它必须默认关闭的原因");
    }

    /**
     * AC3 回归：推理日志关掉后，{@code LoggingToolCallback} 的
     * {@code tool=%s elapsedMs=%d resultChars=%d} INFO 必须照旧存在。
     */
    @Test
    void toolCallLoggingStillWorksWhenReasoningLogsAreOff() {
        Logger toolLogger = (Logger) LoggerFactory.getLogger(LoggingToolCallback.class);
        LoveAgentLoopTest.ScriptedChatModel chatModel = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> LoveAgentLoopTest.hasToolResponse(prompt)
                        ? text("现在是 2026-10-24 18:11（北京时间）")
                        : LoveAgentLoopTest.toolCall("call-1", "currentTime", "{}"));
        AgentProperties properties = LoveAgentLoopTest.inMemoryProperties();
        properties.setLogReasoning(false);
        LoveAgent agent = new LoveAgent(chatModel, properties, new PromptLoader(),
                new AmapMcpToolProvider(properties), new LoveLocalTools(provider(null)));

        List<String> toolLogs = messages(captureInfos(toolLogger, () -> agent.chat("chat-tool", "现在几点了？")));

        assertTrue(toolLogs.stream().anyMatch(log -> log.contains("tool=currentTime")),
                "AC3：工具调用行必须保留，实际：" + toolLogs);
        assertTrue(toolLogs.stream().anyMatch(log -> log.contains("elapsedMs=") && log.contains("resultChars=")),
                "固定格式 tool=/elapsedMs=/resultChars= 不能丢，实际：" + toolLogs);
    }

    private static LoveAgent newAgent(boolean logReasoning, String answer) {
        AgentProperties properties = LoveAgentLoopTest.inMemoryProperties();
        properties.setLogReasoning(logReasoning);
        LoveAgentLoopTest.ScriptedChatModel chatModel =
                new LoveAgentLoopTest.ScriptedChatModel(prompt -> text(answer));
        return new LoveAgent(chatModel, properties, new PromptLoader(),
                new AmapMcpToolProvider(properties), new LoveLocalTools(provider(null)));
    }

    /** 收集指定 logger 在动作期间产生的 INFO 行（其他级别忽略）。 */
    private static List<ILoggingEvent> captureInfos(Logger logger, Runnable action) {
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.INFO).toList();
    }

    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
