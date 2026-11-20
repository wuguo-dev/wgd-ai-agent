package com.example.wjyaiagent.community;

import com.example.wjyaiagent.community.controller.*;
import com.example.wjyaiagent.admin.repository.AdminRepository;
import com.example.wjyaiagent.community.repository.CommunityRepository;
import com.example.wjyaiagent.community.service.CommunityService;
import com.example.wjyaiagent.auth.security.*;
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
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest({CommunityController.class, CommunityAdminController.class})
@Import({AdminRepository.class, SecurityConfig.class, UserRepository.class, CommunityRepository.class, CommunityService.class,
        CommunityExceptionHandler.class, CommunityIntegrationTest.DatabaseConfig.class})
class CommunityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @TestConfiguration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class DatabaseConfig {
        @Bean DataSource dataSource() {
            var db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .setName("community-qa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1").build();
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V001__community_users.sql"),
                    new ClassPathResource("db/migration/V003__student_community.sql"),
                    new ClassPathResource("db/migration/V004__community_rejection_reason.sql"),
                    new ClassPathResource("db/migration/V005__admin_management.sql")).execute(db);
            return db;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource db) { return new JdbcTemplate(db); }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(DataSource db) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(db);
        }
    }

    @BeforeEach void seed() {
        for (String table : List.of("admin_operation_log", "community_favorite", "community_answer", "community_question", "community_category", "community_users")) {
            jdbc.update("DELETE FROM public." + table);
        }
        jdbc.update("INSERT INTO public.community_users(id,username,password_hash,role,status) VALUES "
                + "(1,'alice','$2dummy','STUDENT','ACTIVE'),(2,'bob','$2dummy','STUDENT','ACTIVE'),(3,'moderator','$2dummy','ADMIN','ACTIVE')");
        jdbc.update("INSERT INTO public.community_category(id,name,description,sort_order,status) VALUES(1,'Java','Learn',0,'ENABLED')");
    }

    MockHttpSession session(long id) {
        String role = id == 3 ? "ADMIN" : "STUDENT";
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new StudentPrincipal(new UserInfo(id, id == 1 ? "alice" : id == 2 ? "bob" : "moderator", role),
                        Instant.now().plusSeconds(600)), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    long question() throws Exception {
        String body = mvc.perform(post("/community/questions").session(session(1)).with(csrf())
                .contentType("application/json").content("{\"title\":\"Learning JDBC\",\"content\":\"A useful question\",\"categoryId\":1}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    @Test void authenticationCsrfAndAdminBoundary() throws Exception {
        mvc.perform(get("/community/questions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/community/questions").session(session(1)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/questions").session(session(1))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/categories").session(session(2)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Hacked\",\"description\":\"\",\"sortOrder\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/questions").session(session(3))).andExpect(status().isOk());
    }

    @Test void pendingVisibilityAndConditionalReview() throws Exception {
        long id = question();
        mvc.perform(get("/community/questions").session(session(2))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/community/questions/" + id).session(session(2))).andExpect(status().isNotFound());
        mvc.perform(get("/community/questions/" + id).session(session(1))).andExpect(status().isOk());
        mvc.perform(post("/community/questions/" + id + "/answers").session(session(2)).with(csrf())
                .contentType("application/json").content("{\"content\":\"premature\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/admin/questions/" + id + "/approve").session(session(3)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/admin/questions/" + id + "/approve").session(session(3)).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(get("/community/questions/" + id).session(session(2))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM community_question WHERE id=?", Long.class,id)).isEqualTo(3L);
    }

    @Test void answersAndPersonalDataArePrivateUntilApproved() throws Exception {
        long id = question();
        mvc.perform(post("/admin/questions/" + id + "/approve").session(session(3)).with(csrf())).andExpect(status().isOk());
        var response = mvc.perform(post("/community/questions/" + id + "/answers").session(session(2)).with(csrf())
                .contentType("application/json").content("{\"content\":\"Use prepared statements\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long answer = mapper.readTree(response).get("id").asLong();
        mvc.perform(get("/community/questions/" + id + "/answers").session(session(1))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/community/me/answers").session(session(1))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/community/me/answers").session(session(2))).andExpect(jsonPath("$.total").value(1));
        mvc.perform(post("/admin/answers/" + answer + "/reject").session(session(3)).with(csrf())
                .contentType("application/json").content("{\"reason\":\"Please elaborate\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/community/questions/" + id + "/answers").session(session(2))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/community/me/answers").session(session(2)))
                .andExpect(jsonPath("$.items[0].rejectReason").value("Please elaborate"));
    }

    @Test void validationSearchAndDisabledCategory() throws Exception {
        mvc.perform(post("/community/questions").session(session(1)).with(csrf()).contentType("application/json")
                .content("{\"title\":\"ok\",\"content\":\"ok\",\"categoryId\":1,\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest());
        for (String query : List.of("?page=0", "?size=51", "?keyword=" + "a".repeat(201))) {
            mvc.perform(get("/community/questions" + query).session(session(1))).andExpect(status().isBadRequest());
        }
        long id = question();
        mvc.perform(post("/admin/questions/" + id + "/approve").session(session(3)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/community/questions?keyword=useful&categoryId=1").session(session(1)))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.page").value(1));
        mvc.perform(get("/community/questions?keyword=%25").session(session(1))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(patch("/admin/categories/1/status").session(session(3)).with(csrf()).contentType("application/json")
                .content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
        mvc.perform(post("/community/questions").session(session(1)).with(csrf()).contentType("application/json")
                .content("{\"title\":\"ok\",\"content\":\"ok\",\"categoryId\":1}")).andExpect(status().isConflict());
        mvc.perform(get("/community/questions/" + id).session(session(2))).andExpect(status().isOk());
    }
}
