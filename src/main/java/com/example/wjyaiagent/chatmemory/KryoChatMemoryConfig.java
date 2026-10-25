package com.example.wjyaiagent.chatmemory;

import com.example.wjyaiagent.config.KnowledgeBaseProperties;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KryoChatMemoryConfig {

    @Bean
    KryoChatMemoryRepository kryoChatMemoryRepository(KnowledgeBaseProperties properties) {
        return new KryoChatMemoryRepository(properties.getChat().getMemory().getDirectory());
    }

    @Bean
    ChatMemory knowledgeBaseChatMemory(KryoChatMemoryRepository repository,
                                       KnowledgeBaseProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.getChat().getMemory().getMaxMessages())
                .build();
    }

    @Bean
    Advisor knowledgeBaseChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
