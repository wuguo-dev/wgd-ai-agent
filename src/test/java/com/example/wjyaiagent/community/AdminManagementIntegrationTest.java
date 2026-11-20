package com.example.wjyaiagent.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.wjyaiagent.admin.controller.AdminManagementController;
import com.example.wjyaiagent.admin.repository.AdminRepository;
import com.example.wjyaiagent.admin.service.AdminService;
import com.example.wjyaiagent.auth.security.*;
import com.example.wjyaiagent.community.controller.*;
import com.example.wjyaiagent.community.repository.CommunityRepository;
import com.example.wjyaiagent.community.service.CommunityService;
import com.example.wjyaiagent.user.dto.UserInfo;
import com.example.wjyaiagent.user.repository.UserRepository;

import org.junit.jupiter.api.*;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

@WebMvcTest({
    CommunityController.class,
    CommunityAdminController.class,
    AdminManagementController.class
})
@Import({
    AdminService.class,
    AdminRepository.class,
    SecurityConfig.class,
    UserRepository.class,
    CommunityRepository.class,
    CommunityService.class,
    CommunityExceptionHandler.class,
    AdminManagementIntegrationTest.DatabaseConfig.class
})
class AdminManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @TestConfiguration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            var db =
                    new EmbeddedDatabaseBuilder()
                            .setType(EmbeddedDatabaseType.H2)
                            .setName("admin-qa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                            .build();
            new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/V001__community_users.sql"),
                            new ClassPathResource("db/migration/V003__student_community.sql"),
                            new ClassPathResource(
                                    "db/migration/V004__community_rejection_reason.sql"),
                            new ClassPathResource("db/migration/V005__admin_management.sql"))
                    .execute(db);
            return db;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource db) {
            return new JdbcTemplate(db);
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager(
                DataSource db) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(db);
        }
    }

    @BeforeEach
    void seed() {
        for (String table :
                List.of(
                        "admin_operation_log",
                        "community_favorite",
                        "community_answer",
                        "community_question",
                        "community_category",
                        "community_users")) {
            jdbc.update("DELETE FROM public." + table);
        }
        jdbc.update(
                "INSERT INTO public.community_users(id,username,password_hash,role,status) VALUES "
                    + "(1,'alice','$2dummy','STUDENT','ACTIVE'),(2,'bob','$2dummy','STUDENT','ACTIVE'),(3,'moderator','$2dummy','ADMIN','ACTIVE')");
        jdbc.update(
                "INSERT INTO public.community_category(id,name,description,sort_order,status)"
                    + " VALUES(1,'Java','Learn',0,'ENABLED')");
    }

    MockHttpSession session(long id) {
        String role = id == 3 ? "ADMIN" : "STUDENT";
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new StudentPrincipal(
                                new UserInfo(
                                        id,
                                        id == 1 ? "alice" : id == 2 ? "bob" : "moderator",
                                        role),
                                Instant.now().plusSeconds(600)),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    long question() throws Exception {
        String body =
                mvc.perform(
                                post("/community/questions")
                                        .session(session(1))
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                "{\"title\":\"Learning JDBC\",\"content\":\"A"
                                                    + " useful question\",\"categoryId\":1}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    @Test
    void deleteRestorePreservesStateAndHidesAllStudentEntrypoints() throws Exception {
        long id = question();
        mvc.perform(post("/admin/questions/" + id + "/approve").session(session(3)).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/community/questions/" + id + "/favorite")
                                .session(session(1))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(
                        delete("/admin/questions/" + id)
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"reason\":\"QA moderation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.deleteReason").value("QA moderation"));
        for (String path :
                List.of(
                        "/community/questions",
                        "/community/me/questions",
                        "/community/me/favorites")) {
            mvc.perform(get(path).session(session(1))).andExpect(jsonPath("$.total").value(0));
        }
        mvc.perform(get("/community/questions/" + id).session(session(1)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/community/questions/" + id).session(session(3)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/community/questions/" + id + "/answers")
                                .session(session(2))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"content\":\"hidden\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        delete("/admin/questions/" + id)
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/questions/" + id + "/restore").session(session(3)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/community/me/favorites").session(session(1)))
                .andExpect(jsonPath("$.total").value(1));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM admin_operation_log WHERE"
                                    + " target_type='QUESTION'",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void usersAreSafeAndDisableRevokesCurrentSession() throws Exception {
        var student = session(1);
        mvc.perform(get("/admin/users").session(session(1))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users/1").session(session(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
        mvc.perform(
                        patch("/admin/users/1/status")
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\",\"reason\":\"QA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get("/community/categories").session(student))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        patch("/admin/users/3/status")
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch("/admin/users/1/status")
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM admin_operation_log WHERE target_type='USER'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void statsAndReadonlyAuditAreValidated() throws Exception {
        question();
        mvc.perform(get("/admin/dashboard/stats").session(session(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentTotal").value(2))
                .andExpect(jsonPath("$.questionTotal").value(1))
                .andExpect(jsonPath("$.pendingQuestions").value(1));
        mvc.perform(get("/admin/operation-logs").session(session(1)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/operation-logs?size=51").session(session(3)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/operation-logs?action=UNKNOWN").session(session(3)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/admin/operation-logs?startAt=2026-11-19T00:00:00Z&endAt=2026-11-18T00:00:00Z")
                                .session(session(3)))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/operation-logs/1").session(session(3)).with(csrf()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void categoryAndReviewActionsAreAuditedAndFailedWriteAddsNothing() throws Exception {
        long id = question();
        mvc.perform(
                        post("/admin/questions/" + id + "/reject")
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"reason\":\"Need detail\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/questions/" + id + "/approve").session(session(3)).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(
                        put("/admin/categories/1")
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Java"
                                            + " updated\",\"description\":\"Safe\",\"sortOrder\":1}"))
                .andExpect(status().isOk());
        mvc.perform(
                        patch("/admin/categories/1/status")
                                .session(session(3))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_operation_log", Integer.class))
                .isEqualTo(3);
        mvc.perform(
                        get("/admin/operation-logs?action=QUESTION_REJECT&operatorId=3")
                                .session(session(3)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("Need detail"));
    }
}
