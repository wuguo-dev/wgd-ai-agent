package com.example.wjyaiagent.rag;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(prefix = "knowledge-base.rag", name = "enabled", havingValue = "true")
public class KnowledgeBaseRagAdvisorConfig {

    @Bean
    CitingDocumentRetriever knowledgeBaseDocumentRetriever(
            VectorStore vectorStore,
            @Value("${knowledge-base.rag.retrieval.similarity-threshold:0.50}") double threshold,
            @Value("${knowledge-base.rag.retrieval.top-k:5}") int topK) {
        return new CitingDocumentRetriever(vectorStore, threshold, topK);
    }

    @Bean
    Advisor knowledgeBaseRagAdvisor(
            CitingDocumentRetriever documentRetriever) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                .build();
    }
}
