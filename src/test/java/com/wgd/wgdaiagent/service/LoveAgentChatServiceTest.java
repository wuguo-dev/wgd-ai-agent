package com.wgd.wgdaiagent.service;

import com.wgd.wgdaiagent.agent.LoveAgent;
import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.model.LoveAgentChatRequest;
import com.wgd.wgdaiagent.model.LoveAgentChatResponse;
import com.wgd.wgdaiagent.model.LoveAgentStreamEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T21–T24（规格 §9.3）：接口层业务逻辑，全部离线。
 *
 * <p>用 Mockito 替身代替 {@link LoveAgent}（真内核要 LLM 与 MCP，属 live），
 * 但断言的是 service 自己的契约：chatId 规范化、一次调用、错误码映射、SSE 终止帧、同 chatId 串行。</p>
 */
@Tag("offline")
class LoveAgentChatServiceTest {

    private static final String CHAT_ID = "abc_123";

    private static final String MESSAGE = "北京周末适合约会的地方";

    private final LoveAgent loveAgent = mock(LoveAgent.class);

    private final LoveAgentChatService service = new LoveAgentChatService(loveAgent);

    // ---------- T21 chatId 规范化与同步响应 ----------

    @Test
    void blankChatIdIsGeneratedAndFilledBack() {
        when(loveAgent.chat(anyString(), eq(MESSAGE))).thenReturn("答复");

        LoveAgentChatResponse response = service.chat(new LoveAgentChatRequest("   ", MESSAGE));

        assertThat(response.chatId())
                .hasSize(32)
                .matches("[A-Za-z0-9]{32}");
        assertThat(response.answer()).isEqualTo("答复");
        assertThat(response.elapsedMs()).isGreaterThanOrEqualTo(0L);
        verify(loveAgent, times(1)).chat(response.chatId(), MESSAGE);
    }

    @Test
    void missingChatIdIsAlsoGenerated() {
        when(loveAgent.chat(anyString(), anyString())).thenReturn("答复");

        LoveAgentChatResponse response = service.chat(new LoveAgentChatRequest(null, MESSAGE));

        assertThat(response.chatId()).matches("[A-Za-z0-9]{32}");
        verify(loveAgent).chat(response.chatId(), MESSAGE);
    }

    @Test
    void providedChatIdIsUsedAsIsAndAgentCalledOnce() {
        when(loveAgent.chat(CHAT_ID, MESSAGE)).thenReturn("答复");

        LoveAgentChatResponse response = service.chat(new LoveAgentChatRequest(CHAT_ID, MESSAGE));

        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        assertThat(response.answer()).isEqualTo("答复");
        verify(loveAgent, times(1)).chat(CHAT_ID, MESSAGE);
    }

    @Test
    void invalidChatIdIsRejectedSynchronously() {
        LoveAgentException thrown = assertThrows(LoveAgentException.class,
                () -> service.chat(new LoveAgentChatRequest("../etc/passwd", MESSAGE)));

        assertThat(thrown.getCode()).isEqualTo(AgentErrorCode.INVALID_REQUEST);
        verifyNoInteractions(loveAgent);
    }

    @Test
    void streamRejectsInvalidChatIdBeforeCreatingFlux() {
        // 入口同步抛（不是 error flux）：接口层据此在建流前返回 400
        assertThatThrownBy(() -> service.stream(new LoveAgentChatRequest("../etc/passwd", MESSAGE)))
                .isInstanceOf(LoveAgentException.class);
        verifyNoInteractions(loveAgent);
    }

    // ---------- T22 异常 → 错误码映射 ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("syncFailures")
    void syncFailureIsMappedToErrorCode(RuntimeException failure, AgentErrorCode expected, String expectedMessage) {
        when(loveAgent.chat(anyString(), anyString())).thenThrow(failure);

        LoveAgentException thrown = assertThrows(LoveAgentException.class,
                () -> service.chat(new LoveAgentChatRequest(CHAT_ID, MESSAGE)));

        assertThat(thrown.getCode()).isEqualTo(expected);
        assertThat(thrown.getMessage()).isEqualTo(expectedMessage);
    }

    static Stream<Arguments> syncFailures() {
        return Stream.of(
                Arguments.of(new TransientAiException("429"),
                        AgentErrorCode.AGENT_UNAVAILABLE, "模型服务暂时不可用，请稍后重试"),
                Arguments.of(new NonTransientAiException("401"),
                        AgentErrorCode.AGENT_ERROR, "模型服务拒绝了请求，请稍后重试"),
                Arguments.of(new LoveAgentException(AgentErrorCode.AGENT_LOOP_LIMIT, "循环超限"),
                        AgentErrorCode.AGENT_LOOP_LIMIT, "循环超限"),
                Arguments.of(new LoveAgentException(AgentErrorCode.INTERNAL_ERROR, "内核异常"),
                        AgentErrorCode.INTERNAL_ERROR, "内核异常"),
                Arguments.of(new IllegalStateException("boom"),
                        AgentErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后重试"));
    }

    // ---------- T23 流式事件 ----------

    @Test
    void streamEmitsDeltasThenSingleDone() {
        when(loveAgent.stream(CHAT_ID, MESSAGE)).thenReturn(Flux.just("A", "B"));

        List<LoveAgentStreamEvent> events = service.stream(new LoveAgentChatRequest(CHAT_ID, MESSAGE))
                .collectList()
                .block();

        assertThat(events).isNotNull().hasSize(3);
        assertThat(events.get(0).eventName()).isEqualTo("message");
        assertThat(events.get(0).delta()).isEqualTo("A");
        assertThat(events.get(0).chatId()).isEqualTo(CHAT_ID);
        assertThat(events.get(1).eventName()).isEqualTo("message");
        assertThat(events.get(1).delta()).isEqualTo("B");

        LoveAgentStreamEvent done = events.get(2);
        assertThat(done.eventName()).isEqualTo("done");
        assertThat(done.chatId()).isEqualTo(CHAT_ID);
        assertThat(done.elapsedMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(done.code()).isNull();
        // done 与 error 互斥：整条流里不存在 error 帧
        assertThat(events).noneMatch(event -> event.code() != null);
    }

    @Test
    void streamGeneratesChatIdForBlankRequest() {
        when(loveAgent.stream(anyString(), anyString())).thenReturn(Flux.just("A"));

        List<LoveAgentStreamEvent> events = service.stream(new LoveAgentChatRequest("", MESSAGE))
                .collectList()
                .block();

        assertThat(events).isNotNull().hasSize(2);
        String chatId = events.get(0).chatId();
        assertThat(chatId).matches("[A-Za-z0-9]{32}");
        assertThat(events.get(1).chatId()).isEqualTo(chatId);
        verify(loveAgent).stream(chatId, MESSAGE);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("streamFailures")
    void streamFailureProducesExactlyOneErrorEvent(RuntimeException failure, String expectedCode) {
        when(loveAgent.stream(anyString(), anyString())).thenReturn(Flux.error(failure));

        List<LoveAgentStreamEvent> events = service.stream(new LoveAgentChatRequest(CHAT_ID, MESSAGE))
                .collectList()
                .block();

        assertThat(events).isNotNull().hasSize(1);
        LoveAgentStreamEvent error = events.get(0);
        assertThat(error.eventName()).isEqualTo("error");
        assertThat(error.code()).isEqualTo(expectedCode);
        assertThat(error.chatId()).isEqualTo(CHAT_ID);
        assertThat(error.message()).isNotBlank();
        // error 与 done 互斥：没有 elapsedMs，也没有 delta
        assertThat(error.elapsedMs()).isNull();
        assertThat(error.delta()).isNull();
    }

    static Stream<Arguments> streamFailures() {
        return Stream.of(
                Arguments.of(new TransientAiException("429"), AgentErrorCode.AGENT_UNAVAILABLE.name()),
                Arguments.of(new NonTransientAiException("401"), AgentErrorCode.AGENT_ERROR.name()),
                Arguments.of(new LoveAgentException(AgentErrorCode.AGENT_LOOP_LIMIT, "循环超限"),
                        AgentErrorCode.AGENT_LOOP_LIMIT.name()),
                Arguments.of(new IllegalStateException("boom"), AgentErrorCode.INTERNAL_ERROR.name()));
    }

    // ---------- T24 同 chatId 串行化 ----------

    @Test
    void sameChatIdIsSerializedAcrossConcurrentCalls() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(loveAgent.chat(anyString(), anyString())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(25);
            } finally {
                active.decrementAndGet();
            }
            return "ok";
        });

        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LoveAgentChatResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.chat(new LoveAgentChatRequest(CHAT_ID, MESSAGE));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<LoveAgentChatResponse> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).answer()).isEqualTo("ok");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxActive.get()).isEqualTo(1);
        verify(loveAgent, times(concurrency)).chat(CHAT_ID, MESSAGE);
    }
}
