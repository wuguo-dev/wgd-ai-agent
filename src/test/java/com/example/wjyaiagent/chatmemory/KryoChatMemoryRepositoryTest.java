package com.example.wjyaiagent.chatmemory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KryoChatMemoryRepositoryTest {

    @Test
    void advisorReadsPreviousTurnForTheSameConversation(@TempDir Path directory) {
        KryoChatMemoryRepository repository = new KryoChatMemoryRepository(directory.toString());
        repository.saveAll("chat-2", List.of(
                new UserMessage("我是谁？"),
                new AssistantMessage("你是知识库中的用户画像对象。")));
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
        MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(memory).build();

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("我的专业是什么？")))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "chat-2"))
                .build();
        ChatClientRequest withMemory = advisor.before(request, null);

        assertThat(withMemory.prompt().getInstructions()).extracting(Message::getText)
                .containsExactly("我是谁？", "你是知识库中的用户画像对象。", "我的专业是什么？");
    }

    @Test
    void persistsAndRestoresMultipleTurns(@TempDir Path directory) throws Exception {
        KryoChatMemoryRepository repository = new KryoChatMemoryRepository(directory.toString());
        repository.saveAll("chat-1", List.of(
                new UserMessage("我是谁？"),
                new AssistantMessage("你是知识库中的用户画像对象。"),
                new UserMessage("那我的专业是什么？"),
                new AssistantMessage("软件工程。")));

        List<Message> restored = repository.findByConversationId("chat-1");

        assertThat(Files.list(directory).toList()).hasSize(1);
        assertThat(restored).extracting(Message::getText)
                .containsExactly("我是谁？", "你是知识库中的用户画像对象。", "那我的专业是什么？", "软件工程。");
    }
}
