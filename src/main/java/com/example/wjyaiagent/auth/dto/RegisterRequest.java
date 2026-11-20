package com.example.wjyaiagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "请输入用户名")
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{2,31}", message = "用户名需为3至32位字母、数字或下划线，并以字母开头")
        String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 72, message = "密码需为6至72个字符，且不超过72个UTF-8字节")
        String password,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "请确认密码") @Size(max = 72, message = "确认密码最多72个字符")
        String confirmPassword) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("注册请求包含不支持的字段");
    }

    @Override
    public String toString() {
        return "RegisterRequest[credentials redacted]";
    }
}
