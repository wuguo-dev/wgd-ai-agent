package com.example.wjyaiagent.community.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class CommunityDtos {
    private CommunityDtos() {}

    public interface StrictInput {
        @JsonAnySetter
        default void unknown(String field, Object value) {
            throw new IllegalArgumentException("请求包含不支持的字段");
        }
    }

    public record QuestionInput(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String content,
            @NotNull @Positive Long categoryId)
            implements StrictInput {}

    public record AnswerInput(@NotBlank @Size(max = 10000) String content) implements StrictInput {}

    public record RejectInput(@NotBlank @Size(max = 1000) String reason) implements StrictInput {}

    public record CategoryInput(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            @Min(0) @Max(10000) int sortOrder)
            implements StrictInput {}

    public record StatusInput(@NotNull @Pattern(regexp = "ENABLED|DISABLED") String status)
            implements StrictInput {}

    public record Author(long id, String username) {}

    public record CategoryRef(long id, String name) {}

    public record Category(
            long id,
            String name,
            String description,
            int sortOrder,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record Question(
            long id,
            String title,
            String content,
            CategoryRef category,
            Author author,
            String status,
            String rejectReason,
            Instant createdAt,
            Instant updatedAt,
            boolean favorited,
            Instant deletedAt,
            Long deletedBy,
            String deleteReason) {}

    public record Answer(
            long id,
            long questionId,
            String questionTitle,
            String content,
            Author author,
            String status,
            String rejectReason,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt,
            Long deletedBy,
            String deleteReason,
            boolean questionDeleted) {}

    public record Page<T>(List<T> items, int page, int size, long total) {}
}
