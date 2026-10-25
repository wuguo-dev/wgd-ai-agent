package com.example.wjyaiagent.exception;

import java.util.Objects;

public class KnowledgeBaseException extends RuntimeException {

    private final KnowledgeBaseErrorCode code;

    public KnowledgeBaseException(KnowledgeBaseErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public KnowledgeBaseException(KnowledgeBaseErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public KnowledgeBaseErrorCode getCode() {
        return code;
    }
}
