package com.wgd.wgdaiagent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 恋爱大师 Agent 的问答请求（规格 §7.3）。
 *
 * <p>{@code chatId} 可省略：省略/空白/纯空白时由 service 生成 UUID（去横线）；
 * 一旦给出就必须匹配白名单 {@code ^[A-Za-z0-9_-]{1,64}$}（它同时是 checkpoint 文件名的一部分，R14）。</p>
 *
 * <p>{@code @Size} 的上限必须是编译期常量，所以这里写默认值 2000；运行时实际上限由
 * controller 在进入方法后按 {@code AgentProperties.getMaxMessageLength()} 再校验一次
 * （配置调小时立即生效）。</p>
 *
 * @param chatId  会话 ID，可选
 * @param message 本轮用户消息，必填、trim 后非空、长度受配置约束
 */
public record LoveAgentChatRequest(

        @Pattern(regexp = "^\\s*$|^[A-Za-z0-9_-]{1,64}$",
                message = "chatId 只允许 1-64 位字母、数字、下划线或短横线")
        String chatId,

        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 长度不能超过 2000")
        String message) {
}
