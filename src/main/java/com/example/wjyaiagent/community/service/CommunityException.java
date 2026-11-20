package com.example.wjyaiagent.community.service;

public class CommunityException extends RuntimeException {
    private final int status;
    private final String code;

    public CommunityException(int status, String code, String message) {
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

    public static CommunityException missing() {
        return new CommunityException(404, "NOT_FOUND", "内容不存在或不可见");
    }

    public static CommunityException conflict(String message) {
        return new CommunityException(409, "CONFLICT", message);
    }

    public static CommunityException invalid(String message) {
        return new CommunityException(400, "INVALID_REQUEST", message);
    }
}
