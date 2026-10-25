package com.example.wjyaiagent.controller;

import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import com.example.wjyaiagent.exception.KnowledgeBaseErrorCode;
import com.example.wjyaiagent.exception.KnowledgeBaseException;
import com.example.wjyaiagent.model.KnowledgeChatRequest;
import com.example.wjyaiagent.model.KnowledgeChatResponse;
import com.example.wjyaiagent.model.KnowledgeStreamEvent;
import com.example.wjyaiagent.service.KnowledgeChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeChatController {

    private final KnowledgeChatService chatService;
    private final KnowledgeBaseProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeChatController(KnowledgeChatService chatService,
                                   KnowledgeBaseProperties properties,
                                   ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat")
    public KnowledgeChatResponse chat(@Valid @RequestBody KnowledgeChatRequest request) {
        validateMessageLength(request.message());
        return chatService.chat(request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody KnowledgeChatRequest request) {
        validateMessageLength(request.message());
        Flux<KnowledgeStreamEvent> events = chatService.stream(request);
        SseEmitter emitter = new SseEmitter(properties.getChat().getSseTimeout().toMillis());
        Disposable.Swap subscription = Disposables.swap();
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cancel = () -> {
            closed.set(true);
            subscription.dispose();
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> {
            cancel.run();
            complete(emitter);
        });
        emitter.onError(error -> cancel.run());
        subscription.update(events.subscribeOn(Schedulers.boundedElastic()).subscribe(
                event -> send(emitter, event, closed, cancel),
                error -> {
                    send(emitter, KnowledgeStreamEvent.error(null,
                            KnowledgeBaseErrorCode.INTERNAL_ERROR.name(), "知识库问答服务内部错误，请稍后重试"), closed, cancel);
                    complete(emitter);
                },
                () -> complete(emitter)));
        return emitter;
    }

    private void validateMessageLength(String message) {
        if (message != null && message.length() > properties.getChat().getMaxMessageLength()) {
            throw new KnowledgeBaseException(KnowledgeBaseErrorCode.INVALID_REQUEST,
                    "message 长度不能超过 " + properties.getChat().getMaxMessageLength());
        }
    }

    private void send(SseEmitter emitter, KnowledgeStreamEvent event,
                      AtomicBoolean closed, Runnable cancel) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event.eventName())
                    .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (JsonProcessingException | IllegalStateException error) {
            cancel.run();
            complete(emitter);
        } catch (IOException error) {
            cancel.run();
            complete(emitter);
        }
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // client disconnected
        }
    }
}
