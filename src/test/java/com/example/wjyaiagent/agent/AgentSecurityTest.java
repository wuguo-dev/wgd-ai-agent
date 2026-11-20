package com.example.wjyaiagent.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.wjyaiagent.agent.controller.*;
import com.example.wjyaiagent.agent.repository.AgentRepository;
import com.example.wjyaiagent.agent.service.AgentChatService;
import com.example.wjyaiagent.auth.security.*;
import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import com.example.wjyaiagent.exception.GlobalExceptionHandler;
import com.example.wjyaiagent.model.KnowledgeStreamEvent;
import com.example.wjyaiagent.service.KnowledgeChatService;
import com.example.wjyaiagent.user.dto.UserInfo;
import com.example.wjyaiagent.user.repository.UserRepository;

import org.junit.jupiter.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

@WebMvcTest(AgentChatController.class)
@Import({
    SecurityConfig.class,
    UserRepository.class,
    AgentRepository.class,
    AgentChatService.class,
    AgentExceptionHandler.class,
    GlobalExceptionHandler.class,
    KnowledgeBaseProperties.class,
    AgentSecurityTest.DatabaseConfig.class
})
class AgentSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean KnowledgeChatService chat;
    @MockitoBean ChatMemory memory;

    @TestConfiguration
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            var db =
                    new EmbeddedDatabaseBuilder()
                            .setType(EmbeddedDatabaseType.H2)
                            .setName("agent-web;MODE=PostgreSQL")
                            .build();
            new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/V001__community_users.sql"),
                            new ClassPathResource("db/migration/V002__agent_chat.sql"))
                    .execute(db);
            return db;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource db) {
            return new JdbcTemplate(db);
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM public.agent_message");
        jdbc.update("DELETE FROM public.agent_conversation");
        jdbc.update("DELETE FROM public.community_users");
        jdbc.update(
                "INSERT INTO public.community_users(id,username,password_hash) VALUES"
                        + " (1,'alice','$2dummy'),(2,'bob','$2dummy')");
        jdbc.update(
                "INSERT INTO public.community_users(id,username,password_hash,role) VALUES"
                        + " (3,'moderator','$2dummy','ADMIN'),(4,'reviewer','$2dummy','ADMIN')");
    }

    MockHttpSession session(long id) {
        String role = id >= 3 ? "ADMIN" : "STUDENT";
        String username = Map.of(1L, "alice", 2L, "bob", 3L, "moderator", 4L, "reviewer").get(id);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new StudentPrincipal(
                                new UserInfo(id, username, role), Instant.now().plusSeconds(600)),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    UUID create() throws Exception {
        var result =
                mvc.perform(
                                post("/agent/conversations")
                                        .session(session(1))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content("{}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return UUID.fromString(
                mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void enforcesSessionCsrfAndStrictDtos() throws Exception {
        mvc.perform(get("/agent/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/agent/conversations")
                                .session(session(1))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/agent/conversations")
                                .session(session(1))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"userId\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/conversations?page=-1").session(session(1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parameterConversionErrorsUseAgentErrorContract() throws Exception {
        for (String path :
                List.of(
                        "/agent/conversations/not-a-uuid/messages",
                        "/agent/conversations?page=abc",
                        "/agent/conversations?size=abc")) {
            mvc.perform(get(path).session(session(1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("message").isNotEmpty());
        }
    }

    @Test
    void deniesAllCrossStudentEndpoints() throws Exception {
        var id = create();
        mvc.perform(get("/agent/conversations/" + id + "/messages").session(session(2)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        patch("/agent/conversations/" + id)
                                .session(session(2))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"title\":\"stolen\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/agent/conversations/" + id).session(session(2)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/agent/conversations/" + id + "/cancel").session(session(2)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/agent/conversations/" + id + "/messages/stream")
                                .session(session(2))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"message\":\"steal\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamsAndRestoresPersistedMessages() throws Exception {
        var id = create();
        when(chat.streamForStudent(any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(
                                KnowledgeStreamEvent.delta("internal", "中文回答"),
                                KnowledgeStreamEvent.done("internal", 10, List.of())));
        var result =
                mvc.perform(
                                post("/agent/conversations/" + id + "/messages/stream")
                                        .session(session(1))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content("{\"message\":\"hello\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        mvc.perform(get("/agent/conversations/" + id + "/messages").session(session(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items[1].content").value("中文回答"))
                .andExpect(jsonPath("items[1].status").value("completed"));
    }

    @Test
    void administratorsChatPrivatelyAndCannotAccessOtherOwners() throws Exception {
        var created =
                mvc.perform(
                                post("/agent/conversations")
                                        .session(session(3))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content("{}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        var id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/agent/conversations").session(session(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(1));
        mvc.perform(
                        patch("/agent/conversations/" + id)
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"title\":\"管理员学习会话\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/agent/conversations")
                                .session(session(3))
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        assertOtherOwnerHidden(id, 1);
        assertOtherOwnerHidden(id, 4);
        assertOtherOwnerHidden(create().toString(), 3);

        when(chat.streamForStudent(any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(
                                KnowledgeStreamEvent.delta("internal", "管理员回答"),
                                KnowledgeStreamEvent.done("internal", 10, List.of())));
        var stream =
                mvc.perform(
                                post("/agent/conversations/" + id + "/messages/stream")
                                        .session(session(3))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content("{\"message\":\"解释课程分层\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        mvc.perform(get("/agent/conversations/" + id + "/messages").session(session(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items[1].content").value("管理员回答"));
        mvc.perform(post("/agent/conversations/" + id + "/cancel").session(session(3)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/agent/conversations/" + id).session(session(3)).with(csrf()))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE public.community_users SET status='DISABLED' WHERE id=3");
        mvc.perform(get("/agent/conversations").session(session(3)))
                .andExpect(status().isUnauthorized());
    }

    private void assertOtherOwnerHidden(String id, long owner) throws Exception {
        String path = "/agent/conversations/" + id;
        mvc.perform(get(path + "/messages").session(session(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        patch(path)
                                .session(session(owner))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"title\":\"无权修改\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).session(session(owner)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(path + "/cancel").session(session(owner)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post(path + "/messages/stream")
                                .session(session(owner))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"message\":\"无权读取\"}"))
                .andExpect(status().isNotFound());
    }
}
