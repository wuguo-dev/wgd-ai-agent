package com.example.wjyaiagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeChatServiceTest {

    @Test
    void legacyEndpointsRejectStudentMemoryNamespaceBeforeAnyModelAccess() {
        var providers =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var service = new KnowledgeChatService(null, null, providers);
        for (String chatId :
                java.util.List.of(
                        "student-123e4567-e89b-12d3-a456-426614174000",
                        "Student-123e4567-e89b-12d3-a456-426614174000",
                        "STUDENT-123e4567-e89b-12d3-a456-426614174000",
                        "  student-123e4567-e89b-12d3-a456-426614174000  ")) {
            var request = new com.example.wjyaiagent.model.KnowledgeChatRequest(chatId, "private");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.chat(request))
                    .isInstanceOf(com.example.wjyaiagent.exception.KnowledgeBaseException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.stream(request))
                    .isInstanceOf(com.example.wjyaiagent.exception.KnowledgeBaseException.class);
        }
    }

    @Test
    void removesGeneratedSourceSectionFromAnswer() {
        String answer = "她是软件工程专业学生。\n来源\n04_学校与学生画像_FAQ.md · FAQ-STU-001";

        assertThat(KnowledgeChatService.cleanAnswer(answer)).isEqualTo("她是软件工程专业学生。");
    }
}
