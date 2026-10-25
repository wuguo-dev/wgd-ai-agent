package com.example.wjyaiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "knowledge-base")
public class KnowledgeBaseProperties {

    private Chat chat = new Chat();

    @Getter
    @Setter
    public static class Chat {
        private int maxMessageLength = 2000;
        private Duration sseTimeout = Duration.ofSeconds(60);
        private String systemPrompt = "classpath:prompts/knowledge-base-system.txt";
        private Memory memory = new Memory();
    }

    @Getter
    @Setter
    public static class Memory {
        private String directory = "C:/Users/xdj/.codex/worktrees/knowledge-base-rag/wjy-ai-agent/chat-memory";
        private int maxMessages = 20;
    }
}
