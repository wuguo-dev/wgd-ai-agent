package com.wgd.wgdaiagent.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8（规格 §9.3）：装饰器只打「工具名 + 耗时 + 结果字符数」，结果/定义/异常原样透传。
 */
@Tag("offline")
class LoggingToolCallbackTest {

    private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
            .name("currentTime")
            .description("查询当前时间")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"timeZone\":{\"type\":\"string\"}}}")
            .build();

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingToolCallback.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void delegatesToolDefinition() {
        ToolDefinition definition = DEFINITION;
        LoggingToolCallback callback = new LoggingToolCallback(new StubToolCallback(definition, "ok", null));

        assertSame(definition, callback.getToolDefinition());
        assertEquals("currentTime", callback.getToolDefinition().name());
    }

    @Test
    void delegatesCallAndLogsOnlyNameElapsedAndResultLength() {
        String secretInput = "{\"query\":\"我不方便被别人看到的问题\"}";
        LoggingToolCallback callback = new LoggingToolCallback(
                new StubToolCallback(DEFINITION, "检索结果正文", null));

        assertEquals("检索结果正文", callback.call(secretInput));

        String logLine = singleLogLine();
        assertTrue(logLine.contains("tool=currentTime"), logLine);
        assertTrue(logLine.matches(".*elapsedMs=\\d+.*"), logLine);
        assertTrue(logLine.contains("resultChars=" + "检索结果正文".length()), logLine);
        assertFalse(logLine.contains("我不方便被别人看到的问题"), "禁止打入参正文：" + logLine);
        assertFalse(logLine.contains("检索结果正文"), "禁止打结果正文：" + logLine);
    }

    @Test
    void delegatesCallWithToolContext() {
        LoggingToolCallback callback = new LoggingToolCallback(
                new StubToolCallback(DEFINITION, "带上下文的结果", null));

        assertEquals("带上下文的结果", callback.call("{}", new ToolContext(Map.of("k", "v"))));
        assertTrue(singleLogLine().contains("tool=currentTime"));
    }

    @Test
    void rethrowsOriginalExceptionWithoutSwallowing() {
        IllegalStateException failure = new IllegalStateException("boom");
        LoggingToolCallback callback = new LoggingToolCallback(new StubToolCallback(DEFINITION, null, failure));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> callback.call("{}"));
        assertSame(failure, thrown);
        assertTrue(appender.list.isEmpty(), "异常路径不额外打日志，交给 LoveToolExceptionProcessor 统一 WARN");
    }

    private String singleLogLine() {
        assertEquals(1, appender.list.size(), "每次调用只打一条 INFO");
        return appender.list.get(0).getFormattedMessage();
    }

    /** 假 ToolCallback：返回固定结果或抛固定异常。 */
    private static final class StubToolCallback implements ToolCallback {

        private final ToolDefinition definition;

        private final String result;

        private final RuntimeException failure;

        private StubToolCallback(ToolDefinition definition, String result, RuntimeException failure) {
            this.definition = definition;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
