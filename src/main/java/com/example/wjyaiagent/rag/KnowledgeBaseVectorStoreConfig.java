package com.example.wjyaiagent.rag;

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
public class KnowledgeBaseVectorStoreConfig {

    @Bean
    VectorStore knowledgeBaseVectorStore(EmbeddingModel embeddingModel,
                                         @Qualifier("knowledgeBaseEtlDocuments") List<Document> documents) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        if (!documents.isEmpty()) store.add(documents);
        return store;
    }
}
