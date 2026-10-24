package com.wgd.wgdaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LoveAppRagEtlConfigTest {

    @Test
    void existingMemoryStoreLoadsProcessedDocumentsExactlyOnce() {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ResourcePatternResolver.class, () -> new PathMatchingResourcePatternResolver());
            context.registerBean(EmbeddingModel.class, () -> embeddingModel);
            context.register(LoveAppDocumentLoader.class, LoveAppRagEtlConfig.class, LoveAppVectorStoreConfig.class);
            context.refresh();

            List<Document> chunks = documents(context);
            assertFalse(chunks.isEmpty());
            assertInstanceOf(SimpleVectorStore.class, context.getBean("loveAppVectorStore"));
            assertEquals(chunks, embeddingModel.documents);
            assertEquals(3, chunks.stream().map(document -> document.getMetadata().get("filename"))
                    .collect(Collectors.toSet()).size());
            assertFalse(context.containsBean("loveAppPgVectorEtlRunner"));
        }
    }

    @Test
    void splittingKeepsSourceAndEndOfLongTextAndProducesStableUuidIds() {
        String content = "FIRST_MARKER. " + "Communicate calmly and listen carefully. ".repeat(500) + "LAST_MARKER.";
        LoveAppDocumentLoader loader = new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver()) {
            @Override
            public List<Document> loadMarkdowns() {
                return List.of(new Document(content, Map.of("filename", "long.md", "section", "communication")),
                        new Document(" \n\t ", Map.of("filename", "blank.md")));
            }
        };

        LoveAppRagEtlConfig config = new LoveAppRagEtlConfig();
        List<Document> chunks = config.loveAppEtlDocuments(loader);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.getFirst().getText().contains("FIRST_MARKER"));
        assertTrue(chunks.getLast().getText().contains("LAST_MARKER"));
        assertTrue(chunks.stream().allMatch(document -> "long.md".equals(document.getMetadata().get("filename"))
                && "communication".equals(document.getMetadata().get("section"))));
        chunks.forEach(document -> assertDoesNotThrow(() -> UUID.fromString(document.getId())));
        assertEquals(chunks.stream().map(Document::getId).toList(),
                config.loveAppEtlDocuments(loader).stream().map(Document::getId).toList());
    }

    @Test
    void pgvectorImportIsDisabledUnlessExplicitlyEnabled() {
        try (AnnotationConfigApplicationContext context = pgContext(false, new RecordingEmbeddingModel())) {
            assertFalse(context.containsBean("loveAppVectorStore"));
            assertFalse(context.containsBean("loveAppPgVectorEtlRunner"));
        }
    }

    @Test
    void pgvectorImportUsesTheSelectedStoreAndExistingResourceInjectionStillWorks() throws Exception {
        RecordingEmbeddingModel embeddingModel = new RecordingEmbeddingModel();
        try (AnnotationConfigApplicationContext context = pgContext(true, embeddingModel)) {
            assertFalse(context.containsBean("loveAppVectorStore"));
            assertSame(context.getBean("vectorStore"), context.getBean(StoreConsumer.class).loveAppVectorStore);
            assertTrue(embeddingModel.documents.isEmpty());

            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments(new String[0]));
            assertEquals(documents(context), embeddingModel.documents);
            assertFalse(embeddingModel.documents.isEmpty());
        }
    }

    private AnnotationConfigApplicationContext pgContext(boolean enabled, RecordingEmbeddingModel embeddingModel) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("pgvector");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("etl-test",
                Map.of("love-app.rag.etl.enabled", enabled)));
        context.registerBean(ResourcePatternResolver.class, () -> new PathMatchingResourcePatternResolver());
        context.registerBean("vectorStore", VectorStore.class, () -> SimpleVectorStore.builder(embeddingModel).build());
        context.register(LoveAppDocumentLoader.class, LoveAppRagEtlConfig.class,
                LoveAppVectorStoreConfig.class, StoreConsumer.class);
        context.refresh();
        return context;
    }

    @SuppressWarnings("unchecked")
    private List<Document> documents(AnnotationConfigApplicationContext context) {
        return (List<Document>) context.getBean("loveAppEtlDocuments");
    }

    static class StoreConsumer {
        @Resource
        private VectorStore loveAppVectorStore;
    }

    static class RecordingEmbeddingModel implements EmbeddingModel {
        private final List<Document> documents = new ArrayList<>();

        @Override
        public float[] embed(Document document) {
            documents.add(document);
            return new float[]{1.0f, 0.0f};
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("Offline test must not call a remote model");
        }
    }
}
