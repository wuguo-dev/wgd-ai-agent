package com.example.wjyaiagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class KnowledgeBaseChatConfig implements ResourceLoaderAware {

    private final KnowledgeBaseProperties properties;
    private ResourceLoader resourceLoader;

    public KnowledgeBaseChatConfig(KnowledgeBaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    ChatClient knowledgeBaseChatClient(ChatClient.Builder builder, List<Advisor> advisors) {
        ChatClient.Builder configured = builder.defaultSystem(loadSystemPrompt());
        if (!advisors.isEmpty()) configured.defaultAdvisors(advisors);
        return configured.build();
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    private String loadSystemPrompt() {
        String location = properties.getChat().getSystemPrompt();
        if (location == null || location.isBlank()) return "你是通用知识库问答助手。";
        try {
            Resource resource = resourceLoader.getResource(location);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load knowledge-base system prompt: " + location, exception);
        }
    }
}
