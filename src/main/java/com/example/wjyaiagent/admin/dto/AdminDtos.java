package com.example.wjyaiagent.admin.dto;

import com.example.wjyaiagent.community.dto.CommunityDtos.Author;
import com.example.wjyaiagent.community.dto.CommunityDtos.StrictInput;

import jakarta.validation.constraints.*;

import java.time.Instant;

public final class AdminDtos {
    private AdminDtos() {}

    public record DeleteInput(@NotBlank @Size(max = 1000) String reason) implements StrictInput {}

    public record UserStatusInput(
            @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
            @Size(max = 1000) String reason)
            implements StrictInput {}

    public record User(
            long id,
            String username,
            String role,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record Stats(
            long studentTotal,
            long activeStudents,
            long disabledStudents,
            long questionTotal,
            long answerTotal,
            long pendingQuestions,
            long pendingAnswers,
            long deletedQuestions,
            long deletedAnswers) {}

    public record OperationLog(
            long id,
            Author operator,
            String action,
            String targetType,
            long targetId,
            String reason,
            String beforeState,
            String afterState,
            Instant createdAt) {}

    public record ContentState(
            String status, Instant deletedAt, Long deletedBy, String deleteReason) {}

    public record CategoryState(String name, String description, int sortOrder, String status) {}

    public record UserState(String status) {}
}
