package com.wgd.wgdaiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件体（规格 §6.3）：一条事件一个 JSON，{@code data} 永远是单行 JSON。
 *
 * <p>三种形态互斥：</p>
 * <ul>
 *   <li>{@code message}：{@code {"chatId":"abc","delta":"北京"}}，0..N 次；</li>
 *   <li>{@code done}：{@code {"chatId":"abc","elapsedMs":4210}}，成功时恰好一次且为最后一帧；</li>
 *   <li>{@code error}：{@code {"chatId":"abc","code":"AGENT_UNAVAILABLE","message":"..."}}，
 *       失败时恰好一次且为最后一帧（与 {@code done} 互斥）。</li>
 * </ul>
 *
 * <p>用 {@link JsonInclude.Include#NON_NULL} 把未使用的字段从 JSON 里去掉，保证每帧只有该形态的字段。</p>
 *
 * @param chatId    会话 ID（服务端规范化后的值）
 * @param delta     增量文本；仅 {@code message} 帧非空
 * @param elapsedMs 端到端耗时（毫秒）；仅 {@code done} 帧非空
 * @param code      错误码；仅 {@code error} 帧非空
 * @param message   错误文案；仅 {@code error} 帧非空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoveAgentStreamEvent(String chatId, String delta, Long elapsedMs, String code, String message) {

    /** 增量文本帧。 */
    public static LoveAgentStreamEvent delta(String chatId, String delta) {
        return new LoveAgentStreamEvent(chatId, delta, null, null, null);
    }

    /** 正常结束帧。 */
    public static LoveAgentStreamEvent done(String chatId, long elapsedMs) {
        return new LoveAgentStreamEvent(chatId, null, elapsedMs, null, null);
    }

    /** 失败帧（与 {@code done} 互斥）。 */
    public static LoveAgentStreamEvent error(String chatId, String code, String message) {
        return new LoveAgentStreamEvent(chatId, null, null, code, message);
    }

    /** SSE 事件名：{@code message}/{@code done}/{@code error}。 */
    public String eventName() {
        if (code != null) {
            return "error";
        }
        if (elapsedMs != null) {
            return "done";
        }
        return "message";
    }
}
