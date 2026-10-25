package com.example.wjyaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Configuration
@Slf4j
@ConditionalOnProperty(prefix = "knowledge-base.rag", name = "enabled", havingValue = "true")
public class KnowledgeBaseRagEtlConfig {

    @Bean
    @Lazy
    public List<Document> knowledgeBaseEtlDocuments(KnowledgeBaseDocumentLoader loader,
                                                   @Value("${knowledge-base.rag.retrieval.chunk-size:500}") int chunkSize) {
        List<Document> sourceDocuments = loader.loadMarkdowns().stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkLengthToEmbed(1)
                .withKeepSeparator(true)
                .build();
        List<Document> splitDocuments = splitter.apply(sourceDocuments).stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();

        Map<String, Integer> nextChunkByDocument = new java.util.HashMap<>();
        List<Document> chunks = new ArrayList<>(splitDocuments.size());
        for (Document document : splitDocuments) {
            String documentId = String.valueOf(document.getMetadata().get("documentId"));
            int chunkIndex = nextChunkByDocument.getOrDefault(documentId, 0);
            nextChunkByDocument.put(documentId, chunkIndex + 1);
            Map<String, Object> metadata = new java.util.HashMap<>(document.getMetadata());
            metadata.put("chunkIndex", chunkIndex);
            String identity = metadata.get("knowledgeBaseId") + "|" + documentId + "|"
                    + metadata.get("documentVersion") + "|" + chunkIndex + "|" + document.getText();
            chunks.add(Document.builder()
                    .id(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString())
                    .text(document.getText())
                    .metadata(metadata)
                    .build());
        }
        log.info("knowledge base documents prepared: sourceBlocks={}, chunks={}", sourceDocuments.size(), chunks.size());
        return chunks;
    }

    @Bean
    @Profile("pgvector")
    @ConditionalOnProperty(prefix = "knowledge-base.rag.etl", name = "enabled", havingValue = "true")
    ApplicationRunner knowledgeBaseVectorIngestion(
            @Qualifier("knowledgeBaseEtlDocuments") List<Document> documents,
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
            @Value("${spring.ai.vectorstore.pgvector.table-name:knowledge_base_vector_store}") String tableName,
            @Value("${knowledge-base.rag.knowledge-base-id:default}") String knowledgeBaseId) {
        return args -> ingest(documents, vectorStore, jdbcTemplate,
                schemaName, tableName, knowledgeBaseId);
    }

    private void ingest(List<Document> documents, VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                        String schemaName, String tableName, String knowledgeBaseId) {
        String qualifiedTable = quote(schemaName) + "." + quote(tableName);
        Set<String> discoveredDocumentIds = documents.stream()
                .map(document -> String.valueOf(document.getMetadata().get("documentId")))
                .collect(Collectors.toSet());
        Set<String> existingDocumentIds = new HashSet<>(jdbcTemplate.queryForList(
                "select distinct metadata->>'documentId' from " + qualifiedTable
                        + " where metadata->>'knowledgeBaseId' = ?", String.class, knowledgeBaseId));

        for (String documentId : discoveredDocumentIds) {
            List<Document> current = documents.stream()
                    .filter(document -> documentId.equals(document.getMetadata().get("documentId")))
                    .toList();
            String version = String.valueOf(current.get(0).getMetadata().get("documentVersion"));
            String checksum = String.valueOf(current.get(0).getMetadata().get("checksum"));
            Long existing = jdbcTemplate.queryForObject(
                    "select count(*) from " + qualifiedTable
                            + " where metadata->>'knowledgeBaseId' = ? and metadata->>'documentId' = ?"
                            + " and metadata->>'documentVersion' = ? and metadata->>'checksum' = ?",
                    Long.class, knowledgeBaseId, documentId, version, checksum);
            if (existing != null && existing == current.size()) continue;

            List<String> oldIds = jdbcTemplate.queryForList(
                    "select id from " + qualifiedTable
                            + " where metadata->>'knowledgeBaseId' = ? and metadata->>'documentId' = ?",
                    String.class, knowledgeBaseId, documentId);
            if (!oldIds.isEmpty()) vectorStore.delete(oldIds);
            vectorStore.add(current);
        }

        for (String staleDocumentId : existingDocumentIds) {
            if (!discoveredDocumentIds.contains(staleDocumentId)) {
                List<String> staleIds = jdbcTemplate.queryForList(
                        "select id from " + qualifiedTable
                                + " where metadata->>'knowledgeBaseId' = ? and metadata->>'documentId' = ?",
                        String.class, knowledgeBaseId, staleDocumentId);
                if (!staleIds.isEmpty()) vectorStore.delete(staleIds);
            }
        }
        log.info("knowledge base ingestion completed: documents={}, chunks={}",
                discoveredDocumentIds.size(), documents.size());
    }

    private static String quote(String identifier) {
        if (!identifier.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("invalid SQL identifier");
        return "\"" + identifier + "\"";
    }
}
