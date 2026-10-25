package com.example.wjyaiagent.model;

import java.util.List;

public record KnowledgeChatResponse(String chatId, String answer, long elapsedMs, List<Citation> citations) {
}
