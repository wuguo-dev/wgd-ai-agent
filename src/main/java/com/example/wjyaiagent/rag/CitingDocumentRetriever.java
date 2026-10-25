package com.example.wjyaiagent.rag;

import com.example.wjyaiagent.model.Citation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class CitingDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final double similarityThreshold;
    private final int topK;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<List<Document>>> prepared = new ConcurrentHashMap<>();

    public CitingDocumentRetriever(VectorStore vectorStore, double similarityThreshold, int topK) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
        this.topK = topK;
    }

    public List<Document> prepare(String query) {
        List<Document> documents = retrieveFromStore(query);
        prepared.computeIfAbsent(query, ignored -> new ConcurrentLinkedQueue<>()).add(documents);
        return documents;
    }

    public List<Document> prepare(String query, String fallbackQuery) {
        List<Document> documents = retrieveFromStore(query);
        if (documents.isEmpty() && fallbackQuery != null && !fallbackQuery.isBlank()
                && !fallbackQuery.equals(query)) {
            documents = retrieveFromStore(fallbackQuery);
        }
        prepared.computeIfAbsent(query, ignored -> new ConcurrentLinkedQueue<>()).add(documents);
        if (fallbackQuery != null && !fallbackQuery.isBlank() && !fallbackQuery.equals(query)) {
            prepared.computeIfAbsent(fallbackQuery, ignored -> new ConcurrentLinkedQueue<>()).add(documents);
        }
        return documents;
    }

    @Override
    public List<Document> retrieve(Query query) {
        ConcurrentLinkedQueue<List<Document>> queue = prepared.get(query.text());
        if (queue != null) {
            List<Document> documents = queue.poll();
            if (queue.isEmpty()) prepared.remove(query.text(), queue);
            if (documents != null) return documents;
        }
        return retrieveFromStore(query.text());
    }

    public static List<Citation> citations(List<Document> documents) {
        return documents.stream().map(document -> new Citation(
                value(document, "documentId"),
                value(document, "documentVersion"),
                integerValue(document, "chunkIndex"),
                value(document, "titlePath"),
                value(document, "sourceName"),
                snippet(document.getText()),
                document.getScore())).toList();
    }

    private List<Document> retrieveFromStore(String query) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .similarityThreshold(similarityThreshold)
                .topK(topK)
                .build());
        List<Document> result = documents == null ? List.of() : documents;
        log.debug("knowledge base retrieval completed: documents={}, threshold={}, topK={}",
                result.size(), similarityThreshold, topK);
        return result;
    }

    private static String value(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integerValue(Document document, String key) {
        String value = value(document, key);
        return value == null ? null : Integer.valueOf(value);
    }

    private static String snippet(String text) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
