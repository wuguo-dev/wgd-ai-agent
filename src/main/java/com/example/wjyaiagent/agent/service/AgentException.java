package com.example.wjyaiagent.agent.service;

public class AgentException extends RuntimeException {
    private final int status;
    private final String code;

    public AgentException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
