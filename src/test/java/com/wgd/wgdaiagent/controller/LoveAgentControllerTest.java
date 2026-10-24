package com.wgd.wgdaiagent.controller;

import com.wgd.wgdaiagent.agent.AgentProperties;
import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.model.LoveAgentChatResponse;
import com.wgd.wgdaiagent.model.LoveAgentStreamEvent;
import com.wgd.wgdaiagent.service.LoveAgentChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T25/T26（规格 §9.3）：接口层契约，{@code @WebMvcTest} 切片 + mock service，无网络无 LLM。
 *
 * <p>这里只断言 HTTP 形状（状态码、JSON 字段、{@code text/event-stream}、async 启动）；
 * SSE 帧内容与终止语义由 {@code LoveAgentChatServiceTest}（T23）覆盖，避免重复断言帧顺序。</p>
 */
@WebMvcTest(LoveAgentController.class)
@EnableConfigurationProperties(AgentProperties.class)
@Tag("offline")
class LoveAgentControllerTest {

    private static final String CHAT_PATH = "/ai/love/agent/chat";

    private static final String STREAM_PATH = "/ai/love/agent/chat/stream";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoveAgentChatService chatService;

    // ---------- T25 同步端点 ----------

    @Test
    void syncChatReturns200WithChatIdAnswerAndElapsedMs() throws Exception {
        when(chatService.chat(any())).thenReturn(new LoveAgentChatResponse("abc", "你好呀", 12L));

        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"abc","message":"你好"}"""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.chatId").value("abc"))
                .andExpect(jsonPath("$.answer").value("你好呀"))
                .andExpect(jsonPath("$.elapsedMs").value(12));
    }

    @Test
    void blankMessageReturns400AndSkipsService() throws Exception {
        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("message 不能为空"));

        verifyNoInteractions(chatService);
    }

    @Test
    void missingMessageReturns400() throws Exception {
        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(chatService);
    }

    @Test
    void tooLongMessageReturns400() throws Exception {
        String body = "{\"message\":\"" + "a".repeat(2001) + "\"}";

        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("message 长度不能超过 2000"));

        verifyNoInteractions(chatService);
    }

    @Test
    void invalidChatIdReturns400() throws Exception {
        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"bad id!\",\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("chatId")));

        verifyNoInteractions(chatService);
    }

    @Test
    void agentUnavailableMapsTo503() throws Exception {
        when(chatService.chat(any())).thenThrow(new LoveAgentException(
                AgentErrorCode.AGENT_UNAVAILABLE, "模型服务暂时不可用，请稍后重试"));

        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"abc\",\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_UNAVAILABLE"));
    }

    @Test
    void malformedJsonReturnsUnified400Error() throws Exception {
        mockMvc.perform(post(CHAT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"abc\",\"message\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求体格式不正确，需要 JSON"))
                .andExpect(jsonPath("$.chatId").value(nullValue()));

        verifyNoInteractions(chatService);
    }

    // ---------- T26 流式端点 ----------

    @Test
    void streamStartsAsyncWithEventStreamContentTypeAndFinishesOk() throws Exception {
        Sinks.Many<LoveAgentStreamEvent> sink = Sinks.many().replay().all();
        when(chatService.stream(any())).thenReturn(sink.asFlux());

        MvcResult result = mockMvc.perform(post(STREAM_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"chatId\":\"abc\",\"message\":\"北京周末去哪\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // 用 ASCII delta，避免 MockHttpServletResponse 默认字符集影响断言；帧形状与单行 JSON 仍是关键
        sink.tryEmitNext(LoveAgentStreamEvent.delta("abc", "A"));
        sink.tryEmitNext(LoveAgentStreamEvent.done("abc", 12L));
        sink.tryEmitComplete();

        result.getAsyncResult(5_000L);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:message")))
                .andExpect(content().string(containsString("data:{\"chatId\":\"abc\",\"delta\":\"A\"}")))
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("data:{\"chatId\":\"abc\",\"elapsedMs\":12}")));
    }

    @Test
    void streamThatCompletesImmediatelyOnSubscriptionIsHandledWithoutDangling() throws Exception {
        // 订阅期立刻完成：onCompletion 可能先于 controller 的 swap.update 触发；
        // Swap 保证迟到的 update 立即释放订阅，请求仍正常收尾（不悬挂、不重复发送）。
        when(chatService.stream(any())).thenReturn(
                Flux.just(LoveAgentStreamEvent.done("abc", 5L)));

        MvcResult result = mockMvc.perform(post(STREAM_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"chatId\":\"abc\",\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(5_000L);
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("data:{\"chatId\":\"abc\",\"elapsedMs\":5}")));
    }

    @Test
    void streamValidationFailureReturns400WithoutStartingAsync() throws Exception {
        mockMvc.perform(post(STREAM_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(chatService);
    }
}
