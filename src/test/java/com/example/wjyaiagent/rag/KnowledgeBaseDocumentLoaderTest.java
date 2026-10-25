package com.example.wjyaiagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseDocumentLoaderTest {

    @Test
    void loadsBundledKnowledgeDocumentsFromClasspath() throws Exception {
        Class<?> loaderType = Class.forName("com.example.wjyaiagent.rag.KnowledgeBaseDocumentLoader");
        Object loader = loaderType.getConstructor(String.class, String.class)
                .newInstance("classpath:document", "bundled");
        @SuppressWarnings("unchecked")
        List<Document> documents = (List<Document>) loaderType
                .getMethod("loadMarkdowns").invoke(loader);

        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(document ->
                assertThat(document.getMetadata()).containsEntry("knowledgeBaseId", "bundled"));
    }

    @Test
    void mapsRetrievedMetadataToCitations() {
        Document document = Document.builder()
                .text("Question\nAnswer with evidence")
                .metadata(java.util.Map.of(
                        "documentId", "sample-faq",
                        "documentVersion", "2.0-abc",
                        "chunkIndex", 3,
                        "titlePath", "Sample > FAQ-003",
                        "sourceName", "sample.md"))
                .score(0.87)
                .build();

        var citations = CitingDocumentRetriever.citations(List.of(document));

        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo("sample-faq");
            assertThat(citation.documentVersion()).isEqualTo("2.0-abc");
            assertThat(citation.chunkIndex()).isEqualTo(3);
            assertThat(citation.sourceName()).isEqualTo("sample.md");
            assertThat(citation.score()).isEqualTo(0.87);
        });
    }

    @Test
    void parsesFaqBlocksAndRequiredMetadata(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("sample.md"), """
                ---
                document_id: sample-faq
                title: Sample Knowledge Base
                version: 2.0
                ---
                # Sample Knowledge Base
                ## 使用说明
                This introduction is not a retrievable FAQ block.
                ## FAQ-001: Question one
                **Question**: How do I start?
                **Answer**: Build the basics first.
                ## FAQ-002: Question two
                **Question**: How do I review?
                **Answer**: Review at intervals.
                """, StandardCharsets.UTF_8);

        Class<?> loaderType = Class.forName("com.example.wjyaiagent.rag.KnowledgeBaseDocumentLoader");
        Object loader = loaderType.getConstructor(String.class, String.class)
                .newInstance(directory.toString(), "study");
        @SuppressWarnings("unchecked")
        List<org.springframework.ai.document.Document> documents =
                (List<org.springframework.ai.document.Document>) loaderType
                        .getMethod("loadMarkdowns").invoke(loader);

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getMetadata())
                .containsEntry("knowledgeBaseId", "study")
                .containsEntry("documentId", "sample-faq")
                .containsEntry("sourceName", "sample.md")
                .containsEntry("chunkIndex", 0)
                .containsKey("documentVersion")
                .containsKey("titlePath")
                .containsKey("checksum");
        assertThat(documents.get(0).getText()).contains("How do I start?");
        assertThat(documents.get(1).getMetadata()).containsEntry("chunkIndex", 1);
    }
}
