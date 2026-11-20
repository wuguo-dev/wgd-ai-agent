package com.example.wjyaiagent.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.wjyaiagent.agent.dto.AgentDtos.*;
import com.example.wjyaiagent.agent.repository.AgentRepository;
import com.example.wjyaiagent.agent.service.*;
import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import com.example.wjyaiagent.model.*;
import com.example.wjyaiagent.service.KnowledgeChatService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import reactor.core.publisher.Flux;

import java.util.*;

class AgentChatServiceTest {
    EmbeddedDatabase db;
    AgentRepository repository;
    AgentChatService service;
    KnowledgeChatService chat;
    ChatMemory memory;

    @BeforeEach
    void setup() {
        db =
                new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .setName("agent-" + UUID.randomUUID() + ";MODE=PostgreSQL")
                        .build();
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V001__community_users.sql"),
                        new ClassPathResource("db/migration/V002__agent_chat.sql"))
                .execute(db);
        var jdbc = new JdbcTemplate(db);
        jdbc.update(
                "INSERT INTO public.community_users(id,username,password_hash) VALUES"
                    + " (1,'alice','$2dummy'),(2,'bob','$2dummy')");
        repository = new AgentRepository(jdbc, new ObjectMapper());
        chat = mock(KnowledgeChatService.class);
        memory = mock(ChatMemory.class);
        service = new AgentChatService(repository, chat, memory, new KnowledgeBaseProperties());
    }

    @AfterEach
    void close() {
        db.shutdown();
    }

    @Test
    void isolatesEveryOperationAndRejectsBadPagination() {
        var id = service.create(1, "private").id();
        assertThat(service.list(2, 0, 20).items()).isEmpty();
        for (Runnable action :
                List.<Runnable>of(
                        () -> service.messages(2, id, 0, 20),
                        () -> service.rename(2, id, "stolen"),
                        () -> service.delete(2, id),
                        () -> service.cancel(2, id),
                        () -> service.stream(2, id, "read memory"))) {
            assertThatThrownBy(action::run)
                    .isInstanceOf(AgentException.class)
                    .extracting(e -> ((AgentException) e).status())
                    .isEqualTo(404);
        }
        verifyNoInteractions(memory, chat);
        assertThatThrownBy(() -> service.list(1, -1, 20)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> service.messages(1, id, 0, 101))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void persistsCompletedTurnsAndRebuildsOnlySuccessfulContext() {
        var id = service.create(1, "history").id();
        when(chat.streamForStudent(any()))
                .thenReturn(
                        Flux.just(
                                KnowledgeStreamEvent.delta("internal", "回答"),
                                KnowledgeStreamEvent.done("internal", 1, List.of())));
        var events = service.stream(1, id, "question").collectList().block();
        assertThat(events.getLast().chatId()).isEqualTo(id.toString());
        assertThat(service.messages(1, id, 0, 20).items())
                .extracting(Message::status)
                .containsExactly("completed", "completed");
        assertThat(repository.context(id, 20)).hasSize(2);
        when(chat.streamForStudent(any()))
                .thenReturn(
                        Flux.just(
                                KnowledgeStreamEvent.delta("internal", "partial"),
                                KnowledgeStreamEvent.error("internal", "AI_ERROR", "fail")));
        service.stream(1, id, "failed turn").blockLast();
        assertThat(repository.context(id, 20)).hasSize(2);
        assertThat(service.messages(1, id, 0, 2).items())
                .extracting(Message::status)
                .containsExactly("completed", "failed");
        assertThat(service.messages(1, id, 0, 2).hasMore()).isTrue();
        verify(memory, atLeastOnce()).add(eq("student-" + id), anyList());
        service.recover();
    }

    @Test
    void cancelsAndReleasesBusyConversation() {
        var id = service.create(1, "cancel").id();
        when(chat.streamForStudent(any()))
                .thenReturn(
                        Flux.concat(
                                Flux.just(KnowledgeStreamEvent.delta("internal", "partial")),
                                Flux.never()));
        var subscription = service.stream(1, id, "question").subscribe();
        assertThatThrownBy(() -> service.stream(1, id, "duplicate"))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> service.delete(1, id)).isInstanceOf(AgentException.class);
        service.cancel(1, id);
        assertThat(service.messages(1, id, 0, 20).items().getLast().status())
                .isEqualTo("cancelled");
        assertThat(service.messages(1, id, 0, 20).items().getLast().content()).isEqualTo("partial");
        service.rename(1, id, "available");
        subscription.dispose();
    }

    @Test
    void eofIsInterruptedAndRestartRecoversUnfinishedRows() {
        var id = service.create(1, "incomplete").id();
        when(chat.streamForStudent(any()))
                .thenReturn(Flux.just(KnowledgeStreamEvent.delta("internal", "partial")));
        service.stream(1, id, "question").blockLast();
        assertThat(service.messages(1, id, 0, 20).items().getLast().status())
                .isEqualTo("interrupted");
        repository.start(id, "crashed", UUID.randomUUID());
        service.recover();
        assertThat(service.messages(1, id, 0, 20).items().getLast().status())
                .isEqualTo("interrupted");
    }
}
