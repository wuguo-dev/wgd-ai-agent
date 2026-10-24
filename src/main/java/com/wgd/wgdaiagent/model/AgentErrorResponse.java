package com.wgd.wgdaiagent.model;

/**
 * 统一错误体（规格 §7.3）：{@code {"code":"...","message":"...","chatId":"..."}}。
 *
 * <p>只表达「类别 + 可读文案」，不含堆栈、SQL、URL 或任何内部细节；{@code chatId} 未知时为 {@code null}。</p>
 *
 * @param code    错误码（{@link com.wgd.wgdaiagent.exception.AgentErrorCode} 的名字）
 * @param message 面向调用方的可读文案
 * @param chatId  会话 ID，可能为 {@code null}
 */
public record AgentErrorResponse(String code, String message, String chatId) {
}
