package com.wgd.wgdaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("live")
@SpringBootTest(properties = "love-app.rag.etl.enabled=false")
@ActiveProfiles({"local", "pgvector"})
class LoveAppPgVectorIntegrationTest {

    @Resource(name = "loveAppVectorStore")
    private VectorStore pgVectorVectorStore;

    @Test
    void shouldAddAndSearchDocuments() {
        String testRun = UUID.randomUUID().toString();

        // 为本次测试打标签，避免查询混入已有业务文档。
        Map<String, Object> metadata = Map.of("test_run", testRun);

        List<Document> documents = List.of(
                new Document("Spring AI rocks!! Spring AI rocks!!", metadata),
                new Document(
                        "The World is Big and Salvation Lurks Around the Corner",
                        metadata),
                new Document(
                        "You walk forward facing the past and you turn back toward the future.",
                        metadata)
        );

        List<String> documentIds = documents.stream()
                .map(Document::getId)
                .toList();

        try {
            // 真实调用 Embedding 接口，并写入 PostgreSQL。
            pgVectorVectorStore.add(documents);

            // 只检索本次测试写入的数据。
            List<Document> results = pgVectorVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("Spring")
                            .topK(5)
                            .filterExpression("test_run == '" + testRun + "'")
                            .build()
            );

            assertNotNull(results);
            assertFalse(results.isEmpty(), "应该检索到测试文档");

            String springDocumentId = documents.getFirst().getId();
            assertTrue(
                    results.stream().anyMatch(
                            document -> springDocumentId.equals(document.getId())),
                    "结果应包含刚写入的 Spring AI 文档"
            );
        } finally {
            // 只清理本次生成的文档 ID。
            pgVectorVectorStore.delete(documentIds);
        }
    }
}