package com.wgd.wgdaiagent.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wgd.wgdaiagent.tool.AmapMcpToolProvider;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.provider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
