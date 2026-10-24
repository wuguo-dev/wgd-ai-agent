package com.wgd.wgdaiagent.agent;

import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.emptyMcpToolset;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.mcpProvider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.provider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code stream()} 的错误语义（规格 §7.3 / R2 审查）：流式不能把异常伪装成文本下发。
 *
 * <p>{@code AgentLlmNode} 的流式分支同样会 {@code catch (Exception) → AssistantMessage("Exception: " + msg)}，
 * 所以 SSE 侧必须有哨兵：命中后<b>一个 delta 都不发</b>，直接以错误帧（由 S4 转译）结束。</p>
 */
@Tag("offline")
class LoveAgentStreamErrorTest {

    @Test
    void transientStreamFailureEndsStreamWithOriginalExceptionWithoutText() {
        List<String> deltas = new ArrayList<>();
        LoveAgent agent = agent(failingModel(new TransientAiException("429 Too Many Requests")));

        Throwable error = assertThrows(Throwable.class,
                () -> agent.stream("chat-1", "你好").doOnNext(deltas::add).blockLast(Duration.ofSeconds(30)));

        assertInstanceOf(TransientAiException.class, error, "429 必须原样冒泡，接口层才能映射 503");
        assertTrue(deltas.isEmpty(), "失败前不应已下发文本，实际：" + deltas);
    }

    @Test
    void frameworkErrorTextIsNotStreamedAndBecomesAgentUnavailable() {
        List<String> deltas = new ArrayList<>();
        // 分块下发，模拟 token 流把异常文本吐出来
        LoveAgent agent = agent(chunkedModel("Exce", "ption: ", "429 Too Many Requests"));

        Throwable error = assertThrows(Throwable.class,
                () -> agent.stream("chat-1", "你好").doOnNext(deltas::add).blockLast(Duration.ofSeconds(30)));

        LoveAgentException loveAgentException = assertInstanceOf(LoveAgentException.class, error,
                "框架吞下的异常文本必须以内核异常终止");
        assertEquals(AgentErrorCode.AGENT_UNAVAILABLE, loveAgentException.getCode(), "429 类文本属可重试");
        assertTrue(deltas.isEmpty(), "命中哨兵后一个 delta 都不许发，实际：" + deltas);
    }

    @Test
    void frameworkAuthErrorTextIsClassifiedAsAgentError() {
        LoveAgent agent = agent(chunkedModel("Exception: 401 Unauthorized: InvalidApiKey"));

        Throwable error = assertThrows(Throwable.class,
                () -> agent.stream("chat-1", "你好").blockLast(Duration.ofSeconds(30)));

        LoveAgentException loveAgentException = assertInstanceOf(LoveAgentException.class, error);
        assertEquals(AgentErrorCode.AGENT_ERROR, loveAgentException.getCode(), "401/403 属永久失败");
    }

    @Test
    void textThatOnlyLooksSimilarIsPassedThrough() {
        LoveAgent agent = agent(chunkedModel("Exception 不是异常", "，只是一个英文单词。"));

        List<String> deltas = agent.stream("chat-1", "你好").collectList().block(Duration.ofSeconds(30));

        assertEquals("Exception 不是异常，只是一个英文单词。", String.join("", deltas),
                "只是长得像前缀的正常答复必须原样透传（不能误判、不能丢字）");
    }

    @Test
    void shortAnswerIsFlushedWhenStreamCompletes() {
        LoveAgent agent = agent(chunkedModel("收到"));

        List<String> deltas = agent.stream("chat-1", "你好").collectList().block(Duration.ofSeconds(30));

        assertEquals("收到", String.join("", deltas), "短于哨兵前缀的答复必须在流结束时补发，不能丢");
    }

    // ---------- helpers ----------

    private static LoveAgent agent(ChatModel chatModel) {
        AgentProperties properties = LoveAgentLoopTest.inMemoryProperties();
        return new LoveAgent(chatModel, properties, new PromptLoader(),
                mcpProvider(properties, emptyMcpToolset()), new LoveLocalTools(provider(null)));
    }

    /** 模型流式阶段必抛同一个异常。 */
    private static ChatModel failingModel(RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw failure;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw failure;
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
    }

    /** 按给定分块下发，模拟真实 token 流。 */
    private static ChatModel chunkedModel(String... chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response(String.join("", chunks));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromArray(chunks).map(LoveAgentStreamErrorTest::response);
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
