package com.wgd.wgdaiagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 工具调用日志装饰器（规格 §5.3 规则 2 / §7.4）。
 *
 * <p>每个 callback（本地 2 个 + MCP N 个）都套一层，只打三件事：
 * {@code tool=<name> elapsedMs=<n> resultChars=<n>}。<b>不打入参正文、不打结果正文</b>——用户消息与检索内容属隐私。
 * 结果、元数据与异常一律原样透传，不做任何吞异常或改写。</p>
 */
@Slf4j
public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    public LoggingToolCallback(ToolCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /** 一并委托，避免 {@code returnDirect} 等工具元数据在装饰后丢失。 */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return callAndLog(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return callAndLog(() -> delegate.call(toolInput, toolContext));
    }

    private String callAndLog(Supplier<String> invocation) {
        long startedAt = System.nanoTime();
        String result = invocation.get();
        log.info("tool={} elapsedMs={} resultChars={}",
                toolName(), elapsedMillis(startedAt), result == null ? 0 : result.length());
        return result;
    }

    private String toolName() {
        ToolDefinition definition = delegate.getToolDefinition();
        return definition == null || definition.name() == null ? "unknown" : definition.name();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
