package com.example.wjyaiagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeStreamEvent(String chatId, String delta, Long elapsedMs, String code,
                                   String message, List<Citation> citations) {

    public static KnowledgeStreamEvent delta(String chatId, String delta) {
        return new KnowledgeStreamEvent(chatId, delta, null, null, null, null);
    }

    public static KnowledgeStreamEvent done(String chatId, long elapsedMs, List<Citation> citations) {
        return new KnowledgeStreamEvent(chatId, null, elapsedMs, null, null, citations);
    }

    public static KnowledgeStreamEvent error(String chatId, String code, String message) {
        return new KnowledgeStreamEvent(chatId, null, null, code, message, null);
    }

    public String eventName() {
        if (code != null) return "error";
        if (elapsedMs != null) return "done";
        return "message";
    }
}
