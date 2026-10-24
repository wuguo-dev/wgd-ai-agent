package com.wgd.wgdaiagent.exception;

import java.util.Objects;

/**
 * Agent 内核统一异常（unchecked）。
 *
 * <p>内核只抛这一种「业务异常」；{@code TransientAiException}/{@code NonTransientAiException}
 * 属于模型调用层语义，会原样穿透，由 S4 的异常处理器分别映射 503/502（规格 §7.2）。</p>
 */
public class LoveAgentException extends RuntimeException {

    private final AgentErrorCode code;

    public LoveAgentException(AgentErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public LoveAgentException(AgentErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public AgentErrorCode getCode() {
        return code;
    }
}
