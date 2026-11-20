package com.example.wjyaiagent.agent.dto;

import com.example.wjyaiagent.model.Citation;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AgentDtos {
    private AgentDtos() {}

    public record Conversation(UUID id, String title, Instant createdAt, Instant updatedAt) {}

    public record Message(
            UUID id,
            String role,
            String content,
            String status,
            List<Citation> citations,
            Instant createdAt) {}

    public record Page<T>(List<T> items, int page, int size, boolean hasMore) {}

    public record Create(@Size(min = 1, max = 100) String title) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("未知字段");
        }
    }

    public record Rename(@NotBlank @Size(max = 100) String title) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("未知字段");
        }
    }

    public record Send(@NotBlank @Size(max = 2000) String message) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("未知字段");
        }
    }
}
