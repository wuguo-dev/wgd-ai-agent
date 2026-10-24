package com.wgd.wgdaiagent.model;

/**
 * 同步问答的成功响应（规格 §7.3）：{@code {"chatId":"...","answer":"...","elapsedMs":123}}。
 *
 * <p>{@code chatId} 一定是服务端规范化后的值：请求里没给（或给的空白）时是生成的 UUID。</p>
 *
 * @param chatId    规范化后的会话 ID
 * @param answer    模型最终答复
 * @param elapsedMs 本次调用的端到端耗时（毫秒）
 */
public record LoveAgentChatResponse(String chatId, String answer, long elapsedMs) {
}
