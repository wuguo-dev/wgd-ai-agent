package com.wgd.wgdaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Markdown 知识库 ETL：读取原文、过滤空白、按 Token 切片，再交给 VectorStore 向量化入库。
 * 默认供内存向量库使用；pgvector 模式需显式开启 love-app.rag.etl.enabled 才会导入数据库。
 */
@Configuration
@Slf4j
public class LoveAppRagEtlConfig {

    @Bean
    @Lazy
    public List<Document> loveAppEtlDocuments(LoveAppDocumentLoader documentLoader) {
        // E：复用现有 Markdown 读取器，保留 filename、标题等来源元数据。
        List<Document> sourceDocuments = documentLoader.loadMarkdowns();

        // T：排除空白正文，然后按约 500 Token 的目标大小切片。
        List<Document> documents = sourceDocuments.stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkLengthToEmbed(1)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(documents).stream()
                .filter(document -> StringUtils.hasText(document.getText()))
                .map(document -> {
                    // 相同文件中的相同正文使用相同 UUID，便于持久化存储执行 upsert。
                    String identity = document.getMetadata().get("filename") + "\n" + document.getText();
                    String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
                    return Document.builder()
                            .id(id)
                            .text(document.getText())
                            .metadata(document.getMetadata())
                            .build();
                })
                .toList();

        log.info("ETL 文档处理完成：原始 {} 篇，有效 {} 篇，生成 {} 个片段",
                sourceDocuments.size(), documents.size(), chunks.size());
        return chunks;
    }

    @Bean
    @Profile("pgvector")
    @ConditionalOnProperty(prefix = "love-app.rag.etl", name = "enabled", havingValue = "true")
    public ApplicationRunner loveAppPgVectorEtlRunner(
            @Qualifier("loveAppEtlDocuments") List<Document> documents,
            VectorStore vectorStore) {
        return args -> {
            if (documents.isEmpty()) {
                log.warn("ETL 未找到有效文档，跳过 PostgreSQL 入库");
                return;
            }
            // L：PgVectorStore 内部调用 EmbeddingModel，并将正文、元数据和向量写入 PostgreSQL。
            vectorStore.add(documents);
            log.info("ETL PostgreSQL 入库完成：{} 个片段", documents.size());
        };
    }
}
