package com.wgd.wgdaiagent.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 一次 MCP 工具装配的结果（规格 §4.2）。
 *
 * @param callbacks 已包成 {@link ToolCallback} 的高德工具；不可用时为空列表，绝不为 {@code null}
 * @param available 是否成功连上并拿到工具
 * @param status    成功为 {@code ok}，失败为中文/短原因，仅用于日志与排障
 */
public record McpToolset(List<ToolCallback> callbacks, boolean available, String status) {

    public McpToolset {
        callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
    }
}
