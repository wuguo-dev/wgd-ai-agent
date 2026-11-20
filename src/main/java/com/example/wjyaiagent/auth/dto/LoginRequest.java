package com.example.wjyaiagent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginRequest(
        @NotBlank(message = "请输入用户名") @Size(max = 64, message = "用户名最多64个字符") String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "请输入密码") @Size(max = 72, message = "密码最多72个字符") String password) {
    @Override
    public String toString() {
        return "LoginRequest[credentials redacted]";
    }
}
