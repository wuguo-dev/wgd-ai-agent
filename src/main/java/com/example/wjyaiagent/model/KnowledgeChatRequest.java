package com.example.wjyaiagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KnowledgeChatRequest(
        @Pattern(regexp = "^\\s*$|^[A-Za-z0-9_-]{1,64}$", message = "chatId 只允许 1-64 位字母、数字、下划线或短横线")
        String chatId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 长度不能超过 2000")
        String message) {
}
