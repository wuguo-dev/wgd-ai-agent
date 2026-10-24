package com.wgd.wgdaiagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeoutException;

/**
 * 工具异常 → 模型可读中文文本（规格 §7.2）。
 *
 * <p>工具循环里任何单个工具失败都不该打断整轮回答：这里把异常翻译成一句中文说明回喂模型，
 * 让它「基于已有信息继续回答，并如实告知用户该信息暂时无法获取」。</p>
 *
 * <p>安全约束（§7.4）：回喂文本里只出现工具名 + 原因类别，<b>不带异常 message</b>
 * （可能含 SQL/URL/凭据）、不带堆栈；WARN 日志同样只记异常简单类名。</p>
 */
@Slf4j
public class LoveToolExceptionProcessor implements ToolExecutionExceptionProcessor {

    private static final String UNKNOWN_TOOL = "unknown";

    private static final String DEFAULT_CATEGORY = "服务暂时不可用";

    private static final String MESSAGE_TEMPLATE =
            "工具 %s 调用失败：%s。请基于已有信息继续回答，并如实告知用户该信息暂时无法获取。";

    @Override
    public String process(ToolExecutionException exception) {
        String toolName = toolName(exception);
        Throwable cause = exception != null && exception.getCause() != null ? exception.getCause() : exception;
        String category = cause == null ? DEFAULT_CATEGORY : category(cause);
        log.warn("工具 {} 调用失败（{}），已转为可读文本回喂模型",
                toolName, cause == null ? "unknown" : cause.getClass().getSimpleName());
        return MESSAGE_TEMPLATE.formatted(toolName, category);
    }

    private static String toolName(ToolExecutionException exception) {
        if (exception == null) {
            return UNKNOWN_TOOL;
        }
        ToolDefinition definition = exception.getToolDefinition();
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            return UNKNOWN_TOOL;
        }
        return definition.name();
    }

    /** 只按异常类型归类，绝不拼接 message。 */
    private static String category(Throwable cause) {
        if (cause instanceof TimeoutException) {
            return "请求超时";
        }
        if (cause instanceof IOException || cause instanceof UncheckedIOException) {
            return "网络或外部服务暂时不可达";
        }
        if (cause instanceof InterruptedException) {
            return "请求被中断";
        }
        if (cause instanceof IllegalArgumentException) {
            return "参数不合法";
        }
        if (cause instanceof UnsupportedOperationException) {
            return "当前环境不支持该操作";
        }
        return DEFAULT_CATEGORY;
    }
}
