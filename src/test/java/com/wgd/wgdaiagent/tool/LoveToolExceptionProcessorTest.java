package com.wgd.wgdaiagent.tool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T7（规格 §9.3）：工具异常 → 中文可读文本，含工具名，不含异常 message/堆栈/SQL/URL。
 */
@Tag("offline")
class LoveToolExceptionProcessorTest {

    private final LoveToolExceptionProcessor processor = new LoveToolExceptionProcessor();

    @Test
    void translatesExceptionIntoToolNamedChineseTextAndHidesCauseMessage() {
        ToolExecutionException exception = new ToolExecutionException(
                definition("loveKbSearch"),
                new RuntimeException("secret jdbc url jdbc:postgresql://db-host/love password=secret"));

        String message = processor.process(exception);

        assertTrue(message.contains("loveKbSearch"), "必须带工具名，模型才能说清是哪项能力不可用：" + message);
        assertTrue(message.contains("调用失败"), message);
        assertTrue(message.contains("请基于已有信息继续回答"), message);
        assertTrue(message.contains("服务暂时不可用"), "按异常类别给原因：" + message);

        assertFalse(message.contains("secret"), "异常 message 不得回喂模型");
        assertFalse(message.contains("jdbc"), "SQL/URL 不得回喂模型");
        assertFalse(message.contains("RuntimeException"), "不暴露异常类名");
        assertFalse(message.contains("\tat "), "不带堆栈");
        assertFalse(message.contains("\n"), "必须是单段可读文本");
    }

    @Test
    void classifiesTimeoutAndIoCauses() {
        assertTrue(processor.process(new ToolExecutionException(
                definition("maps_weather"), new TimeoutException("read timed out"))).contains("请求超时"));
        assertTrue(processor.process(new ToolExecutionException(
                definition("maps_weather"), new IOException("https://mcp.amap.com/sse?key=secret"))).contains("网络"));
    }

    @Test
    void keepsToolNameWhenCauseMessageIsAbsent() {
        ToolExecutionException exception = new ToolExecutionException(definition("currentTime"), new RuntimeException());

        String message = processor.process(exception);

        assertTrue(message.contains("currentTime"), message);
        assertTrue(message.endsWith("请基于已有信息继续回答，并如实告知用户该信息暂时无法获取。"), message);
    }

    @Test
    void unknownToolNameFallsBackToPlaceholder() {
        ToolDefinition nameless = new ToolDefinition() {
            @Override
            public String name() {
                return null;
            }

            @Override
            public String description() {
                return "无名字的假工具定义";
            }

            @Override
            public String inputSchema() {
                return "{}";
            }
        };

        String message = processor.process(new ToolExecutionException(nameless, new IllegalStateException("boom")));

        assertTrue(message.startsWith("工具 unknown 调用失败："), message);
    }

    private static ToolDefinition definition(String name) {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(name + " 的描述")
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }
}
