package com.example.wjyaiagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChatServiceTest {

    @Test
    void removesGeneratedSourceSectionFromAnswer() {
        String answer = "她是软件工程专业学生。\n来源\n04_学校与学生画像_FAQ.md · FAQ-STU-001";

        assertThat(KnowledgeChatService.cleanAnswer(answer)).isEqualTo("她是软件工程专业学生。");
    }
}
