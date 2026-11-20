package com.example.wjyaiagent.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wjyaiagent.admin.dto.AdminDtos.UserStatusInput;
import com.example.wjyaiagent.admin.repository.AdminRepository;
import com.example.wjyaiagent.admin.service.AdminService;
import com.example.wjyaiagent.community.repository.CommunityRepository;
import com.example.wjyaiagent.community.service.CommunityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@SpringJUnitConfig(AdminAuditRollbackTest.DatabaseConfig.class)
class AdminAuditRollbackTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CommunityService community;
    @Autowired AdminService admin;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
        CommunityRepository.class,
        CommunityService.class,
        AdminRepository.class,
        AdminService.class
    })
    static class DatabaseConfig {
        @Bean(destroyMethod = "shutdown")
        org.springframework.jdbc.datasource.embedded.EmbeddedDatabase dataSource() {
            var database =
                    new EmbeddedDatabaseBuilder()
                            .setType(EmbeddedDatabaseType.H2)
                            .setName("admin-audit-rollback;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                            .build();
            new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/V001__community_users.sql"),
                            new ClassPathResource("db/migration/V003__student_community.sql"),
                            new ClassPathResource(
                                    "db/migration/V004__community_rejection_reason.sql"),
                            new ClassPathResource("db/migration/V005__admin_management.sql"))
                    .execute(database);
            return database;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource database) {
            return new JdbcTemplate(database);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource database) {
            return new DataSourceTransactionManager(database);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().addModule(new JavaTimeModule()).build();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM public.admin_operation_log");
        jdbc.update("DELETE FROM public.community_question");
        jdbc.update("DELETE FROM public.community_category");
        jdbc.update("DELETE FROM public.community_users");
        jdbc.update(
                "INSERT INTO public.community_users(id,username,password_hash,role,status) VALUES "
                        + "(1,'audit_student','$2fixture','STUDENT','ACTIVE'),"
                        + "(2,'audit_admin','$2fixture','ADMIN','ACTIVE')");
        jdbc.update("INSERT INTO public.community_category(id,name) VALUES (1,'Audit rollback')");
        jdbc.update(
                "INSERT INTO public.community_question(id,user_id,category_id,title,content) "
                        + "VALUES (1,1,1,'Pending question','A question awaiting review')");
    }

    @Test
    void auditInsertFailureRollsBackQuestionApprovalAndReviewMetadata() {
        jdbc.execute(
                "ALTER TABLE public.admin_operation_log ADD CONSTRAINT fail_review_audit "
                        + "CHECK (action <> 'QUESTION_APPROVE')");
        try {
            // The valid review UPDATE runs before this deliberately failing audit INSERT.
            assertThatThrownBy(() -> community.reviewQuestion(1, 2, true, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM public.community_question WHERE id=1",
                                    String.class))
                    .isEqualTo("PENDING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT reviewed_by FROM public.community_question WHERE id=1",
                                    Long.class))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT reviewed_at FROM public.community_question WHERE id=1",
                                    java.sql.Timestamp.class))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.admin_operation_log",
                                    Integer.class))
                    .isZero();
        } finally {
            jdbc.execute(
                    "ALTER TABLE public.admin_operation_log DROP CONSTRAINT fail_review_audit");
        }
        // A retry after the database fault succeeds and produces one success log.
        assertThat(community.reviewQuestion(1, 2, true, null).status()).isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.admin_operation_log", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void auditInsertFailureRollsBackStudentDisableAndAllowsRetry() {
        jdbc.execute(
                "ALTER TABLE public.admin_operation_log ADD CONSTRAINT fail_user_audit "
                        + "CHECK (action <> 'USER_DISABLE')");
        try {
            assertThatThrownBy(
                            () ->
                                    admin.userStatus(
                                            1,
                                            2,
                                            new UserStatusInput("DISABLED", "Audit failure test")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM public.community_users WHERE id=1",
                                    String.class))
                    .isEqualTo("ACTIVE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.admin_operation_log",
                                    Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE public.admin_operation_log DROP CONSTRAINT fail_user_audit");
        }
        assertThat(
                        admin.userStatus(1, 2, new UserStatusInput("DISABLED", "Retry after fault"))
                                .status())
                .isEqualTo("DISABLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.admin_operation_log", Integer.class))
                .isEqualTo(1);
    }
}
