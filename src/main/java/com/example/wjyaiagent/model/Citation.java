package com.example.wjyaiagent.model;

public record Citation(String documentId, String documentVersion, Integer chunkIndex,
                       String titlePath, String sourceName, String snippet, Double score) {
}
