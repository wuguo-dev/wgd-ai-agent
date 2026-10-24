package com.wgd.wgdaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.util.ClassUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("offline")
class LoveAppPgVectorStoreConfigTest {

    @Resource
    VectorStore pgVectorVectorStore;

    @Test
    void createsNamedVectorStoreWithoutPgvectorAutoConfigurationOrDatabaseAccess() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        try (AnnotationConfigApplicationContext context = context(jdbc, Map.of())) {
            VectorStore store = context.getBean("loveAppVectorStore", VectorStore.class);
            assertInstanceOf(PgVectorStore.class, store);
            assertSame(jdbc, store.getNativeClient().orElseThrow());
            assertTrue(jdbc.statements.isEmpty(), "未开启 schema 初始化时不应执行 DDL");
            assertFalse(ClassUtils.isPresent(
                    "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                    getClass().getClassLoader()));
        }
    }

    @Test
    void explicitlyEnabledSchemaInitializationUsesConfiguredTableDimensionsAndIndexWithoutDroppingData() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        Map<String, Object> properties = Map.of(
                "spring.ai.vectorstore.pgvector.initialize-schema", true,
                "spring.ai.vectorstore.pgvector.schema-name", "etl_test",
                "spring.ai.vectorstore.pgvector.table-name", "manual_vectors",
                "spring.ai.vectorstore.pgvector.dimensions", 6,
                "spring.ai.vectorstore.pgvector.index-type", "HNSW",
                "spring.ai.vectorstore.pgvector.distance-type", "NEGATIVE_INNER_PRODUCT");

        try (AnnotationConfigApplicationContext context = context(jdbc, properties)) {
            assertNotNull(context.getBean("loveAppVectorStore", PgVectorStore.class));
            assertEquals(6, jdbc.statements.size(), "Spring 应只初始化一次：三个扩展、schema、表和索引");
            assertTrue(jdbc.statements.stream().anyMatch(sql -> sql.contains("CREATE TABLE")
                    && sql.contains("etl_test") && sql.contains("manual_vectors") && sql.contains("vector(6)")));
            assertTrue(jdbc.statements.stream().anyMatch(sql -> sql.contains("CREATE INDEX")
                    && sql.contains("HNSW") && sql.contains("vector_ip_ops")));
            assertTrue(jdbc.statements.stream().noneMatch(sql -> sql.contains("DROP TABLE")));
        }
    }

    private AnnotationConfigApplicationContext context(RecordingJdbcTemplate jdbc, Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("pgvector");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("manual-pgvector-test", properties));
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(EmbeddingModel.class, () -> new OfflineEmbeddingModel());
        context.register(LoveAppPgVectorStoreConfig.class);
        context.refresh();
        return context;
    }

    static class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> statements = new ArrayList<>();

        RecordingJdbcTemplate() {
            super(new AbstractDataSource() {
                @Override
                public Connection getConnection() {
                    throw new AssertionError("离线测试禁止连接数据库");
                }

                @Override
                public Connection getConnection(String username, String password) {
                    throw new AssertionError("离线测试禁止连接数据库");
                }
            });
        }

        @Override
        public void execute(String sql) {
            statements.add(sql);
        }
    }

    static class OfflineEmbeddingModel implements EmbeddingModel {
        @Override
        public float[] embed(Document document) {
            throw new AssertionError("初始化 VectorStore 不应向量化文档");
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new AssertionError("离线测试禁止请求模型");
        }
    }


}
