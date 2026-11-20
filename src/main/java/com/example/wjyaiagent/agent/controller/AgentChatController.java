package com.example.wjyaiagent.agent.controller;

import com.example.wjyaiagent.agent.dto.AgentDtos.*;
import com.example.wjyaiagent.agent.service.AgentChatService;
import com.example.wjyaiagent.auth.security.StudentPrincipal;
import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/agent/conversations")
public class AgentChatController {
    private final AgentChatService service;
    private final ObjectMapper mapper;
    private final KnowledgeBaseProperties properties;

    public AgentChatController(
            AgentChatService service, ObjectMapper mapper, KnowledgeBaseProperties properties) {
        this.service = service;
        this.mapper = mapper;
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(
            @AuthenticationPrincipal StudentPrincipal principal,
            @Valid @RequestBody Create request) {
        return service.create(principal.user().id(), request.title());
    }

    @GetMapping
    public Page<Conversation> list(
            @AuthenticationPrincipal StudentPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(principal.user().id(), page, size);
    }

    @GetMapping("/{id}/messages")
    public Page<Message> messages(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.messages(principal.user().id(), id, page, size);
    }

    @PatchMapping("/{id}")
    public Conversation rename(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody Rename request) {
        return service.rename(principal.user().id(), id, request.title());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal StudentPrincipal principal, @PathVariable UUID id) {
        service.delete(principal.user().id(), id);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal StudentPrincipal principal, @PathVariable UUID id) {
        service.cancel(principal.user().id(), id);
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal StudentPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody Send request) {
        var events = service.stream(principal.user().id(), id, request.message());
        SseEmitter emitter =
                new SseEmitter(properties.getChat().getSseTimeout().plusSeconds(5).toMillis());
        var subscription = Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onError(error -> subscription.dispose());
        emitter.onTimeout(
                () -> {
                    subscription.dispose();
                    emitter.complete();
                });
        subscription.update(
                events.subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                event -> {
                                    try {
                                        emitter.send(
                                                SseEmitter.event()
                                                        .name(event.eventName())
                                                        .data(
                                                                mapper.writeValueAsString(event),
                                                                MediaType.APPLICATION_JSON));
                                    } catch (Exception error) {
                                        subscription.dispose();
                                        emitter.complete();
                                    }
                                },
                                error -> emitter.complete(),
                                emitter::complete));
        return emitter;
    }
}
