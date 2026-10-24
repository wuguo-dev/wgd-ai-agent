package com.wgd.wgdaiagent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 手动创建 PostgreSQL 向量库，不依赖 PGVector Starter 的自动配置。
 * DataSource / JdbcTemplate 仍由 JDBC Starter 根据 spring.datasource 配置提供。
 */
@Configuration
@Profile("pgvector")
public class LoveAppPgVectorStoreConfig {

    @Bean
    public PgVectorStore loveAppVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel dashscopeEmbeddingModel,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}") int dimensions,
            @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
            @Value("${spring.ai.vectorstore.pgvector.table-name:love_app_vector_store}") String tableName,
            @Value("${spring.ai.vectorstore.pgvector.index-type:HNSW}") PgIndexType indexType,
            @Value("${spring.ai.vectorstore.pgvector.distance-type:COSINE_DISTANCE}") PgDistanceType distanceType,
            @Value("${spring.ai.vectorstore.pgvector.initialize-schema:false}") boolean initializeSchema) {

        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(dimensions)
                .schemaName(schemaName)
                .vectorTableName(tableName)
                .indexType(indexType)
                .distanceType(distanceType)
                .initializeSchema(initializeSchema)
                .removeExistingVectorStoreTable(false)
                .build();
        // Spring 会调用 PgVectorStore 的 afterPropertiesSet()，无需手动重复初始化。
    }

}
