package com.wgd.wgdaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
@Profile("!pgvector")
public class LoveAppVectorStoreConfig {

    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel,
            @Qualifier("loveAppEtlDocuments") List<Document> documents) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
                .build();
        // L：ETL 配置负责读取和切片，向量存储负责向量化及写入。
        if (!documents.isEmpty()) {
            simpleVectorStore.add(documents);
        }
        return simpleVectorStore;
    }
}
