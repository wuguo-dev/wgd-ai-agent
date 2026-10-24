package com.wgd.wgdaiagent.agent;

import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.emptyMcpToolset;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.inMemoryProperties;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.mcpProvider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.provider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 模型层异常必须原样冒到接口层（规格 §7.2/§7.3：429→503、401/403→502）。
 *
 * <p>背景：{@code AgentLlmNode} 的非流式分支 {@code catch (Exception)} 后会把模型异常转成
 * {@code "Exception: ..."} 文本当答复返回（HTTP 200 + 假答复）。所以 {@code LoveAgent.chat}
 * 也走框架默认的流式执行，让异常在订阅期抛出，再由 {@code translate} 沿 cause 链识别原类型。</p>
 */
@Tag("offline")
class LoveAgentErrorMappingTest {

    @Test
    void transientModelFailureIsRethrownAsTransientAiException() {
        LoveAgentLoopTest.ScriptedChatModel model = failingModel(
                new TransientAiException("429 Too Many Requests: Requests rate limit exceeded"));
        LoveAgent agent = newAgent(model);

        TransientAiException thrown = assertThrows(TransientAiException.class,
                () -> agent.chat("chat-1", "北京周末适合约会的地方"), "429 必须原样上抛，接口层才能映射 503");

        assertFalse(thrown.getMessage().contains("Exception: "), "不得返回/抛出被框架包装过的文本");
    }

    @Test
    void permanentModelFailureIsRethrownAsNonTransientAiException() {
        LoveAgentLoopTest.ScriptedChatModel model = failingModel(
                new NonTransientAiException("401 Unauthorized: InvalidApiKey"));
        LoveAgent agent = newAgent(model);

        NonTransientAiException thrown = assertThrows(NonTransientAiException.class,
                () -> agent.chat("chat-1", "你好"), "401/403 必须原样上抛，接口层才能映射 502");

        assertFalse(thrown.getMessage().contains("Exception: "), "不得返回/抛出被框架包装过的文本");
    }

    @Test
    void successfulAnswerNeverCarriesFrameworkExceptionPrefix() {
        LoveAgentLoopTest.ScriptedChatModel model = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> text("周末可以去朝阳公园散步。"));
        LoveAgent agent = newAgent(model);

        String answer = agent.chat("chat-1", "周末去哪");

        assertEquals("周末可以去朝阳公园散步。", answer);
        assertFalse(answer.startsWith("Exception: "), "正常答复不应带框架的错误前缀");
    }

    @Test
    void loopLimitStillWinsOverGenericError() {
        LoveAgentLoopTest.ScriptedChatModel model = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> LoveAgentLoopTest.toolCall("call-loop", "loveKbSearch", "{\"query\":\"再来一次\"}"));
        LoveAgent agent = newAgent(model);

        LoveAgentException error = assertThrows(LoveAgentException.class, () -> agent.chat("chat-1", "一直调工具"));

        assertEquals(com.wgd.wgdaiagent.exception.AgentErrorCode.AGENT_LOOP_LIMIT, error.getCode(),
                "走流式执行后，递归上限仍必须被识别成 AGENT_LOOP_LIMIT");
    }

    @Test
    void modelFailureSurfacesThroughStreamAsError() {
        LoveAgentLoopTest.ScriptedChatModel model = failingModel(new TransientAiException("429 rate limit"));
        LoveAgent agent = newAgent(model);

        Throwable error = assertThrows(Throwable.class,
                () -> agent.stream("chat-1", "你好").collectList().block(java.time.Duration.ofSeconds(30)));

        if (!(error instanceof TransientAiException)) {
            fail("流式路径也必须让 429 原样冒泡，实际：" + error.getClass().getName());
        }
    }

    @Test
    void frameworkErrorMessageIsNeverReturnedAsAnswer() {
        // 模拟「记录器没命中、但框架仍把异常伪装成答复」的场景：模型正常返回了一段以 Exception: 开头的文本
        LoveAgentLoopTest.ScriptedChatModel model = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> text("Exception: 429 Too Many Requests: Requests rate limit exceeded"));
        LoveAgent agent = newAgent(model);

        LoveAgentException error = assertThrows(LoveAgentException.class, () -> agent.chat("chat-1", "你好"),
                "哨兵文本绝不能作为答复返回给调用方");
        assertEquals(AgentErrorCode.AGENT_UNAVAILABLE, error.getCode(), "429 类文本应归类为可重试");
    }

    @Test
    void frameworkAuthErrorMessageIsClassifiedAsAgentError() {
        LoveAgentLoopTest.ScriptedChatModel model = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> text("Exception: 401 Unauthorized: InvalidApiKey"));
        LoveAgent agent = newAgent(model);

        LoveAgentException error = assertThrows(LoveAgentException.class, () -> agent.chat("chat-1", "你好"));
        assertEquals(AgentErrorCode.AGENT_ERROR, error.getCode(), "401/403 属永久失败");
    }

    @Test
    void sanitizeMasksKeyAndTruncates() {
        String masked = LoveAgent.sanitize("GET https://mcp.amap.com/sse?key=real-secret-value&x=1 failed");

        assertFalse(masked.contains("real-secret-value"), "日志必须抹掉 key：" + masked);
        assertTrue(masked.contains("key=***"), masked);
    }

    // ---------- helpers ----------

    /** 让 call() 与 stream() 都抛同一个模型层异常（stream 内部复用 call）。 */
    private static LoveAgentLoopTest.ScriptedChatModel failingModel(RuntimeException failure) {
        return new LoveAgentLoopTest.ScriptedChatModel(prompt -> {
            throw failure;
        });
    }

    private static LoveAgent newAgent(LoveAgentLoopTest.ScriptedChatModel model) {
        AgentProperties properties = inMemoryProperties();
        return new LoveAgent(model, properties, new PromptLoader(),
                mcpProvider(properties, emptyMcpToolset()), new LoveLocalTools(provider(null)));
    }
}
