package com.example.wjyaiagent.agent.service;

import com.example.wjyaiagent.agent.dto.AgentDtos.*;
import com.example.wjyaiagent.agent.repository.AgentRepository;
import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import com.example.wjyaiagent.model.*;
import com.example.wjyaiagent.service.KnowledgeChatService;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;

@Service
public class AgentChatService {
    private final AgentRepository repository;
    private final KnowledgeChatService chat;
    private final ChatMemory memory;
    private final KnowledgeBaseProperties properties;
    private final Map<UUID, Generation> active = new java.util.concurrent.ConcurrentHashMap<>();

    public AgentChatService(
            AgentRepository repository,
            KnowledgeChatService chat,
            ChatMemory memory,
            KnowledgeBaseProperties properties) {
        this.repository = repository;
        this.chat = chat;
        this.memory = memory;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.recover();
    }

    public Conversation create(long owner, String title) {
        String value = title == null ? "新对话" : title.trim();
        if (value.isEmpty()) throw new AgentException(400, "INVALID_REQUEST", "标题不能为空");
        return repository.create(owner, value);
    }

    public Page<Conversation> list(long owner, int page, int size) {
        validatePage(page, size);
        return page(repository.list(owner, page, size), page, size, false);
    }

    public Page<Message> messages(long owner, UUID id, int page, int size) {
        own(owner, id);
        validatePage(page, size);
        return page(repository.messages(id, page, size), page, size, true);
    }

    public synchronized Conversation rename(long owner, UUID id, String title) {
        own(owner, id);
        idle(id);
        if (title.isBlank()) throw new AgentException(400, "INVALID_REQUEST", "标题不能为空");
        repository.rename(owner, id, title.trim());
        return own(owner, id);
    }

    public synchronized void delete(long owner, UUID id) {
        own(owner, id);
        idle(id);
        memory.clear(key(id));
        repository.delete(owner, id);
    }

    public void cancel(long owner, UUID id) {
        own(owner, id);
        Generation generation = active.get(id);
        if (generation != null) {
            generation.cancelled = true;
            generation.cancel.tryEmitEmpty();
        }
    }

    public synchronized Flux<KnowledgeStreamEvent> stream(long owner, UUID id, String input) {
        own(owner, id);
        idle(id);
        if (input.length() > properties.getChat().getMaxMessageLength())
            throw new AgentException(400, "INVALID_REQUEST", "问题过长");
        Generation generation = new Generation();
        active.put(id, generation);
        try {
            memory.clear(key(id));
            var context = repository.context(id, properties.getChat().getMemory().getMaxMessages());
            if (!context.isEmpty())
                memory.add(
                        key(id),
                        context.stream()
                                .map(
                                        m ->
                                                m.role().equals("user")
                                                        ? (org.springframework.ai.chat.messages
                                                                        .Message)
                                                                new UserMessage(m.content())
                                                        : new AssistantMessage(m.content()))
                                .toList());
            repository.start(id, input, generation.answer);
        } catch (RuntimeException error) {
            active.remove(id);
            throw error;
        }
        return Flux.defer(() -> chat.streamForStudent(new KnowledgeChatRequest(key(id), input)))
                .map(
                        event ->
                                new KnowledgeStreamEvent(
                                        id.toString(),
                                        event.delta(),
                                        event.elapsedMs(),
                                        event.code(),
                                        event.message(),
                                        safeCitations(event.citations())))
                .doOnNext(event -> accept(id, generation, event))
                .takeUntilOther(
                        Mono.firstWithSignal(
                                generation.cancel.asMono(),
                                Mono.delay(properties.getChat().getSseTimeout())))
                .onErrorResume(
                        error -> {
                            finish(id, generation, "failed", List.of());
                            return Flux.just(
                                    KnowledgeStreamEvent.error(
                                            id.toString(), "INTERNAL_ERROR", "回答生成失败，请重试"));
                        })
                .doFinally(
                        signal ->
                                finish(
                                        id,
                                        generation,
                                        generation.cancelled
                                                        || signal
                                                                == reactor.core.publisher.SignalType
                                                                        .CANCEL
                                                ? "cancelled"
                                                : "interrupted",
                                        List.of()));
    }

    private void accept(UUID id, Generation generation, KnowledgeStreamEvent event) {
        synchronized (generation) {
            if (generation.finished) return;
            if (event.delta() != null) {
                if (generation.content.length() + event.delta().length() > 200_000)
                    throw new AgentException(502, "AI_ERROR", "回答超过长度限制");
                generation.content.append(event.delta());
            }
            if (event.code() != null) finish(id, generation, "failed", List.of());
            else if (event.elapsedMs() != null)
                finish(id, generation, "completed", event.citations());
        }
    }

    private void finish(UUID id, Generation generation, String status, List<Citation> citations) {
        synchronized (generation) {
            if (generation.finished) return;
            generation.finished = true;
            try {
                repository.finish(
                        id, generation.answer, generation.content.toString(), status, citations);
            } finally {
                try {
                    memory.clear(key(id));
                } finally {
                    active.remove(id, generation);
                }
            }
        }
    }

    private Conversation own(long owner, UUID id) {
        return repository
                .find(owner, id)
                .orElseThrow(() -> new AgentException(404, "NOT_FOUND", "会话不存在"));
    }

    private void idle(UUID id) {
        if (active.containsKey(id))
            throw new AgentException(409, "CONVERSATION_BUSY", "请等待生成结束或停止生成");
    }

    private static String key(UUID id) {
        return "student-" + id;
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new AgentException(400, "INVALID_REQUEST", "分页参数无效");
    }

    private static <T> Page<T> page(List<T> rows, int page, int size, boolean reverse) {
        boolean more = rows.size() > size;
        var items = new ArrayList<>(rows.subList(0, Math.min(rows.size(), size)));
        if (reverse) Collections.reverse(items);
        return new Page<>(items, page, size, more);
    }

    private static List<Citation> safeCitations(List<Citation> citations) {
        if (citations == null) return null;
        return citations.stream()
                .map(
                        c ->
                                new Citation(
                                        safe(c.documentId()),
                                        safe(c.documentVersion()),
                                        c.chunkIndex(),
                                        safe(c.titlePath()),
                                        safe(c.sourceName()),
                                        safeSnippet(c.snippet()),
                                        c.score()))
                .toList();
    }

    private static String safe(String value) {
        if (value == null) return null;
        String normalized = value.replace('\\', '/');
        return normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
    }

    private static String safeSnippet(String value) {
        if (value == null) return null;
        return value.replaceAll("(?i)[a-z]:[\\\\/][^\\s<>\"']+", "[路径已隐藏]")
                .replaceAll(
                        "(?<![a-zA-Z0-9:])/(?:home|root|etc|var|usr|opt|mnt|srv|tmp)/[^\\s<>\"']+",
                        "[路径已隐藏]");
    }

    private static class Generation {
        final UUID answer = UUID.randomUUID();
        final StringBuilder content = new StringBuilder();
        final Sinks.One<Void> cancel = Sinks.one();
        boolean finished;
        volatile boolean cancelled;
    }
}
