package com.wgd.wgdaiagent.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgd.wgdaiagent.agent.AgentProperties;
import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.model.LoveAgentChatRequest;
import com.wgd.wgdaiagent.model.LoveAgentChatResponse;
import com.wgd.wgdaiagent.model.LoveAgentStreamEvent;
import com.wgd.wgdaiagent.service.LoveAgentChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 恋爱大师 Agent 的两个 HTTP 端点（规格 §6.2 / §6.3 / §7.3）。
 *
 * <p>只做接口层该做的事：Bean Validation、{@link SseEmitter} 生命周期、OpenAPI 注解；
 * 业务（chatId 生成、调 agent、组响应、异常翻译）全在 {@link LoveAgentChatService}。</p>
 *
 * <p>注意 context-path 已是 {@code /api}，所以这里只写 {@code /ai/love/agent}。
 * 本类必须留在 {@code com.wgd.wgdaiagent.controller} 包：Knife4j 只扫这个包（R15）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/ai/love/agent")
@Tag(name = "恋爱大师 Agent", description = "同步问答与 SSE 流式问答；chatId 可选，用于多轮会话")
public class LoveAgentController {

    private static final String INTERNAL_MESSAGE = "服务内部错误，请稍后重试";

    private final LoveAgentChatService chatService;

    private final AgentProperties properties;

    private final ObjectMapper objectMapper;

    public LoveAgentController(LoveAgentChatService chatService,
                               AgentProperties properties,
                               ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步问答：阻塞到 agent 给出最终答复后一次性返回 JSON。
     *
     * @param request 请求体（{@code chatId} 可选、{@code message} 必填）
     * @return {@code {"chatId":"...","answer":"...","elapsedMs":123}}
     */
    @PostMapping("/chat")
    @Operation(summary = "同步问答", description = "阻塞直到 agent 给出最终答复；失败按错误码返回 4xx/5xx")
    public LoveAgentChatResponse chat(@Valid @RequestBody LoveAgentChatRequest request) {
        validateMessageLength(request.message());
        return chatService.chat(request);
    }

    /**
     * 流式问答（SSE）：立刻返回 {@link SseEmitter}，事件在 reactor 的 boundedElastic 上推送。
     *
     * <p>两类失败的位置刻意不同：</p>
     * <ul>
     *   <li>Bean Validation / 消息长度 / 服务入口同步抛（如非法 chatId）→ 建流前 400 JSON，不建流；</li>
     *   <li>建流之后的失败 → {@code event:error} 一帧后结束（HTTP 状态已无法更改，§7.3）。</li>
     * </ul>
     *
     * @param request 请求体（同同步接口）
     * @return 已启动异步处理的 {@code text/event-stream} 响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式问答（SSE）",
            description = "事件 message/done/error；data 为单行 JSON；done 与 error 互斥且只出现一次")
    public SseEmitter chatStream(@Valid @RequestBody LoveAgentChatRequest request) {
        validateMessageLength(request.message());
        // 先取流：chatId 规范化等入口错误在这里同步抛出，从而在「建流之前」变成 400/503 JSON
        Flux<LoveAgentStreamEvent> events = chatService.stream(request);

        SseEmitter emitter = new SseEmitter(properties.getSseTimeout().toMillis());
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<String> lastChatId = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable cancel = () -> {
            closed.set(true);
            Disposable current = subscription.get();
            if (current != null) {
                current.dispose();
            }
        };
        // 客户端断开/超时/错误：取消订阅即可；检查点已由框架按节点写入，不回滚（§4.4 / R12）
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> {
            cancel.run();
            completeQuietly(emitter);
        });
        emitter.onError(error -> cancel.run());

        subscription.set(events
                // 不占用 servlet 请求线程：订阅整体挪到 boundedElastic，controller 立即返回 emitter
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(event -> {
                            if (event.chatId() != null) {
                                lastChatId.set(event.chatId());
                            }
                            send(emitter, event, closed, cancel);
                        },
                        error -> {
                            // 兜底：service 已把异常翻译成 error 帧，正常不会走到这里
                            log.error("SSE 事件流异常终止", error);
                            send(emitter, LoveAgentStreamEvent.error(lastChatId.get(),
                                    AgentErrorCode.INTERNAL_ERROR.name(), INTERNAL_MESSAGE), closed, cancel);
                            completeQuietly(emitter);
                        },
                        () -> completeQuietly(emitter)));
        return emitter;
    }

    /** 建流前校验：上限取 {@code AgentProperties.getMaxMessageLength()}，默认 2000。 */
    private void validateMessageLength(String message) {
        int max = properties.getMaxMessageLength();
        if (message != null && message.length() > max) {
            throw new LoveAgentException(AgentErrorCode.INVALID_REQUEST, "message 长度不能超过 " + max);
        }
    }

    /**
     * 发送一帧 SSE。{@code data} 用 ObjectMapper 序列化成单行 JSON，不依赖 MVC 的消息转换器。
     *
     * <p>客户端断开表现为 {@link IOException}（emitter 已结束时是 {@link IllegalStateException}）：
     * 只取消订阅并停止后续发送，不写任何回滚逻辑。</p>
     */
    private void send(SseEmitter emitter, LoveAgentStreamEvent event, AtomicBoolean closed, Runnable cancel) {
        if (closed.get()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .name(event.eventName())
                    .data(json, MediaType.APPLICATION_JSON));
        } catch (JsonProcessingException error) {
            log.error("SSE 事件序列化失败（{}）", error.getClass().getSimpleName());
            cancel.run();
            completeQuietly(emitter);
        } catch (IOException | IllegalStateException error) {
            log.debug("SSE 发送失败（{}），取消订阅", error.getClass().getSimpleName());
            cancel.run();
            completeQuietly(emitter);
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 响应已结束（例如客户端已断开），无需处理
        }
    }
}
