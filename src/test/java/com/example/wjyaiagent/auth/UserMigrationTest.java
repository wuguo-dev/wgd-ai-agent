package com.example.wjyaiagent.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import static org.assertj.core.api.Assertions.assertThat;

class UserMigrationTest {
    @Test
    void migrationPreservesExistingTablesAndUserOnRerun() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE").build();
        try {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE rag_sentinel(id INT PRIMARY KEY)");
            jdbc.update("INSERT INTO rag_sentinel VALUES (47)");
            var flyway = Flyway.configure().dataSource(database).defaultSchema("public")
                    .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
            jdbc.update("INSERT INTO public.community_users(username,password_hash) VALUES (?,?)", "retained", "$2b$fixture");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.community_users WHERE username='retained'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT id FROM rag_sentinel", Integer.class)).isEqualTo(47);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("005");
        } finally {
            database.shutdown();
        }
    }
}


