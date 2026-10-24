package com.wgd.wgdaiagent.exception;

/**
 * Agent 内核对外暴露的错误码（规格 §7.3）。
 *
 * <p>本枚举只表达「内核视角的失败类别」，到 HTTP 状态码的映射由 S4 的
 * {@code GlobalExceptionHandler} 负责（{@code INVALID_REQUEST}→400、
 * {@code AGENT_UNAVAILABLE}→503、{@code AGENT_ERROR}→502、
 * {@code AGENT_LOOP_LIMIT}/{@code INTERNAL_ERROR}→500）。</p>
 */
public enum AgentErrorCode {

    /** 参数不合法（例如 {@code chatId} 不符合白名单）。 */
    INVALID_REQUEST,

    /** 模型暂时不可用（限流/重试耗尽），可重试。 */
    AGENT_UNAVAILABLE,

    /** 模型永久性失败或图执行失败，不可重试。 */
    AGENT_ERROR,

    /** 工具循环达到递归上限仍未给出最终答复。 */
    AGENT_LOOP_LIMIT,

    /** 兜底：未预期异常。 */
    INTERNAL_ERROR
}
