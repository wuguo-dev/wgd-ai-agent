package com.example.wjyaiagent.auth;

import com.example.wjyaiagent.auth.controller.*;
import com.example.wjyaiagent.auth.security.*;
import com.example.wjyaiagent.auth.service.AuthService;
import com.example.wjyaiagent.controller.KnowledgeChatController;
import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import com.example.wjyaiagent.exception.GlobalExceptionHandler;
import com.example.wjyaiagent.model.KnowledgeChatResponse;
import com.example.wjyaiagent.security.SecurityProperties;
import com.example.wjyaiagent.service.KnowledgeChatService;
import com.example.wjyaiagent.user.dto.UserInfo;
import com.example.wjyaiagent.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, KnowledgeChatController.class}, properties = {
        "knowledge-base.security.api-key=regression-api-key",
        "knowledge-base.security.rate-limit-burst=1", "knowledge-base.security.rate-limit-per-minute=0"
})
@Import({com.example.wjyaiagent.security.ApiKeyFilter.class, com.example.wjyaiagent.security.RateLimitFilter.class, SecurityConfig.class, AuthService.class, UserRepository.class, LoginThrottle.class,
        AuthExceptionHandler.class, GlobalExceptionHandler.class, KnowledgeBaseProperties.class,
        AuthIntegrationTest.DatabaseConfig.class})
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean KnowledgeChatService chat;

    @TestConfiguration
    static class DatabaseConfig {
        @Bean DataSource dataSource() {
            var db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .setName("auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1").build();
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V001__community_users.sql")).execute(db);
            // Running the versioned migration twice must preserve existing rows.
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V001__community_users.sql")).execute(db);
            return db;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean SecurityProperties securityProperties() {
            var properties = new SecurityProperties();
            properties.setApiKey("regression-api-key");
            properties.setRateLimitBurst(1);
            properties.setRateLimitPerMinute(0);
            return properties;
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM community_users");
        jdbc.update("ALTER TABLE community_users ALTER COLUMN id RESTART WITH 100");
        jdbc.update("INSERT INTO community_users(id,username,password_hash,role,status) VALUES(1,?,?,?,?)",
                "student", encoder.encode("Test-password-2026"), "STUDENT", "ACTIVE");
        jdbc.update("INSERT INTO community_users(id,username,password_hash,role,status) VALUES(2,?,?,?,?)",
                "disabled", encoder.encode("Test-password-2026"), "STUDENT", "DISABLED");
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/auth/login").with(csrf()).contentType("application/json")
                .content("{\"username\":\"student\",\"password\":\"Test-password-2026\"}")
                .with(request -> { request.setRemoteAddr(java.util.UUID.randomUUID().toString()); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("username").value("student"))
                .andExpect(jsonPath("passwordHash").doesNotExist()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test void loginRestoreAndLogout() throws Exception {
        var session = login();
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("role").value("STUDENT"));
        // A second independent request simulates restoring identity after a reload.
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void credentialsHaveUniformFailure() throws Exception {
        String expected = null;
        for (String payload : List.of(
                "{\"username\":\"student\",\"password\":\"wrong\"}",
                "{\"username\":\"missing\",\"password\":\"wrong\"}",
                "{\"username\":\"disabled\",\"password\":\"Test-password-2026\"}")) {
            var body = mvc.perform(post("/auth/login").with(csrf()).contentType("application/json").content(payload))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            if (expected == null) expected = body;
            assertThat(body).isEqualTo(expected);
        }
    }

    @Test void validatesEmptyAndMalformedInputs() throws Exception {
        for (String payload : List.of("{}", "{\"username\":\" \",\"password\":\"a\"}",
                "{\"username\":\"student\",\"password\":\"\"}", "{")) {
            mvc.perform(post("/auth/login").with(csrf()).contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        }
    }

    @Test void enforcesAuthenticationRolesAndCsrf() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/private")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("token").isNotEmpty());
        mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var session = login();
        mvc.perform(get("/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/auth/logout").session(session)).andExpect(status().isForbidden());
        jdbc.update("UPDATE community_users SET status='DISABLED' WHERE id=1");
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void expiredSessionCannotAccess() throws Exception {
        var session = login();
        var principal = new StudentPrincipal(new UserInfo(1, "student", "STUDENT"), Instant.now().minusSeconds(1));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void rotatesSessionOnLogin() throws Exception {
        var anonymous = new MockHttpSession();
        var result = mvc.perform(post("/auth/login").session(anonymous).with(csrf())
                .with(request -> { request.setRemoteAddr("rotation"); return request; })
                .contentType("application/json")
                .content("{\"username\":\"student\",\"password\":\"Test-password-2026\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(anonymous.isInvalid()).isTrue();
        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(anonymous.getId());
    }

    @Test void limitsBruteForce() throws Exception {
        for (int attempt = 0; attempt < 11; attempt++) {
            mvc.perform(post("/auth/login").with(csrf()).contentType("application/json")
                    .with(request -> { request.setRemoteAddr("throttle-test"); return request; })
                    .content("{\"username\":\"missing\",\"password\":\"wrong\"}"))
                    .andExpect(status().is(attempt < 10 ? 401 : 429));
        }
    }

    @Test void registrationPersistsHashAndRequiresExplicitLogin() throws Exception {
        String payload = "{\"username\":\"new_student\",\"password\":\"Register-test-2026\",\"confirmPassword\":\"Register-test-2026\"}";
        mvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("role").value("STUDENT"))
                .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("passwordHash").doesNotExist());
        String hash = jdbc.queryForObject("SELECT password_hash FROM community_users WHERE username='new_student'", String.class);
        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("Register-test-2026", hash)).isTrue();
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("USERNAME_EXISTS"));
        var result = mvc.perform(post("/auth/login").with(csrf()).contentType("application/json")
                .content("{\"username\":\"new_student\",\"password\":\"Register-test-2026\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
    }

    @Test void rejectsInvalidRegistrationAndPrivilegeFields() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var valid = java.util.Map.of("username", "valid_student", "password", "Register-test-2026",
                "confirmPassword", "Register-test-2026");
        for (var change : java.util.List.of(
                java.util.Map.of("username", ""), java.util.Map.of("username", "1bad"),
                java.util.Map.of("password", "weak"), java.util.Map.of("password", "      "), java.util.Map.of("password", "12345"),
                java.util.Map.of("confirmPassword", "Mismatch-2026"),
                java.util.Map.of("password", "Ab1" + "中".repeat(30), "confirmPassword", "Ab1" + "中".repeat(30)),
                java.util.Map.of("role", "ADMIN"), java.util.Map.of("status", "ACTIVE"),
                java.util.Map.of("password_hash", "injected"))) {
            var input = new java.util.HashMap<>(valid);
            input.putAll(change);
            mvc.perform(post("/auth/register").with(csrf()).contentType("application/json")
                    .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_users", Integer.class)).isEqualTo(2);
        mvc.perform(post("/auth/register").contentType("application/json").content(mapper.writeValueAsString(valid)))
                .andExpect(status().isUnauthorized());
    }

    @Test void acceptsSixCharacterPasswordsWithoutCompositionRequirements() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        int index = 0;
        for (String password : List.of("123456", "abcdef", "ABCDEF", "!!!!!!", "中文密码六位")) {
            String username = "simple_password_" + index++;
            mvc.perform(post("/auth/register").with(csrf()).contentType("application/json")
                    .with(request -> { request.setRemoteAddr(username); return request; })
                    .content(mapper.writeValueAsString(java.util.Map.of(
                            "username", username, "password", password, "confirmPassword", password))))
                    .andExpect(status().isCreated());
            String hash = jdbc.queryForObject(
                    "SELECT password_hash FROM community_users WHERE username=?", String.class, username);
            assertThat(encoder.matches(password, hash)).isTrue();
            mvc.perform(post("/auth/login").with(csrf()).contentType("application/json")
                    .with(request -> { request.setRemoteAddr(username); return request; })
                    .content(mapper.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                    .andExpect(status().isOk());
        }
    }

    @Test void registrationLimitsRepeatedAttempts() throws Exception {
        for (int attempt = 0; attempt < 11; attempt++) {
            mvc.perform(post("/auth/register").with(csrf()).contentType("application/json")
                    .with(request -> { request.setRemoteAddr("register-throttle"); return request; })
                    .content("{\"username\":\"register_limit\",\"password\":\"Register-test-2026\",\"confirmPassword\":\"Mismatch-2026\"}"))
                    .andExpect(status().is(attempt < 10 ? 400 : 429));
        }
    }

    @Test void concurrentRegistrationCreatesExactlyOneStudent() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> register = () -> {
                start.await();
                return mvc.perform(post("/auth/register").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"concurrent_student\",\"password\":\"Register-test-2026\",\"confirmPassword\":\"Register-test-2026\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(register);
            var second = executor.submit(register);
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_users WHERE username='concurrent_student'", Integer.class))
                .isEqualTo(1);
    }

    @Test void aiRetainsApiKeyAndRateLimitWithoutStudentLogin() throws Exception {
        when(chat.chat(any())).thenReturn(new KnowledgeChatResponse("test-chat", "answer", 1, List.of()));
        mvc.perform(post("/ai/knowledge/chat").servletPath("/ai/knowledge/chat").contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/ai/knowledge/chat").servletPath("/ai/knowledge/chat").header("X-API-Key", "regression-api-key")
                .contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("answer").value("answer"));
        mvc.perform(post("/ai/knowledge/chat").servletPath("/ai/knowledge/chat").header("X-API-Key", "regression-api-key")
                .contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/ai/knowledge/chat/stream").servletPath("/ai/knowledge/chat/stream").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
