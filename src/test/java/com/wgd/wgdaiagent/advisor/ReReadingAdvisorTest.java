package com.wgd.wgdaiagent.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReReadingAdvisor 纯单元测试：不启动 Spring、不调用模型接口。
 * 用来证明改写确实生效（而不是"能编译但空操作"），且不污染历史消息。
 */
class ReReadingAdvisorTest {

    private static final String HISTORY_QUESTION = "你好，我是wgd。我捡到了一只猫，我给他取名叫咖啡！";
    private static final String CURRENT_QUESTION = "我捡到的猫叫什么名字？";

    private final ReReadingAdvisor advisor = new ReReadingAdvisor();

    /** 模拟第三轮对话：system + 历史问答 + 本轮提问 */
    private Prompt multiTurnPrompt() {
        return new Prompt(List.of(
                new SystemMessage("SYSTEM"),
                new UserMessage(HISTORY_QUESTION),
                new AssistantMessage("你好，Wgd"),
                new UserMessage(CURRENT_QUESTION)));
    }

    private ChatClientRequest rewrite(ChatClientRequest request) {
        // before() 不使用 chain，传 null 即可
        return advisor.before(request, null);
    }

    @Test
    void rewritesOnlyTheLastUserMessage() {
        Prompt result = rewrite(ChatClientRequest.builder().prompt(multiTurnPrompt()).build()).prompt();
        List<Message> messages = result.getInstructions();

        assertEquals(4, messages.size(), "消息条数不应变化");
        assertEquals(HISTORY_QUESTION, messages.get(1).getText(), "历史用户消息必须保持原样，不能也被复读");
        assertEquals("你好，Wgd", messages.get(2).getText(), "助手历史消息必须保持原样");

        String expected = CURRENT_QUESTION + "\nRead the question again: " + CURRENT_QUESTION + "\n";
        assertEquals(expected, messages.get(3).getText(), "最后一条用户消息应被改写为 Re2 模板");
    }

    @Test
    void keepsConversationContext() {
        ChatClientRequest original = ChatClientRequest.builder()
                .prompt(multiTurnPrompt())
                .context("chat_memory_conversation_id", "chat-123")
                .build();

        assertEquals("chat-123", rewrite(original).context().get("chat_memory_conversation_id"),
                "改写后必须保留会话 ID，否则记忆功能会失效");
    }

    @Test
    void keepsUserMessageMetadata() {
        // 用自定义 key：messageType 是框架保留字，会被 AbstractMessage 覆写成 MessageType 枚举
        UserMessage withMeta = UserMessage.builder()
                .text(CURRENT_QUESTION)
                .metadata(Map.of("traceId", "abc-123"))
                .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage("SYSTEM"), withMeta));

        Message rewritten = rewrite(ChatClientRequest.builder().prompt(prompt).build())
                .prompt().getInstructions().get(1);

        assertEquals("abc-123", rewritten.getMetadata().get("traceId"), "自定义 metadata 不能丢");
        assertEquals(MessageType.USER, rewritten.getMetadata().get("messageType"), "框架的 messageType 也应保留");
    }

    @Test
    void sitsOutsideLoggerButInsideMemoryAdvisor() {
        // order 越小越靠外，before() 由外向内执行。
        // Re2 要比记忆 Advisor 大（排它内层，历史才干净），
        // 又比日志 Advisor 小（排它外层，日志才拍得到复读后的 prompt）。
        int order = new ReReadingAdvisor().getOrder();

        assertTrue(order > Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER,
                "必须排在记忆 Advisor 内层，否则复读文本会被写进历史");
        assertTrue(order < new SimpleLoggerAdvisor().getOrder(),
                "必须排在日志 Advisor 外层，否则日志拍到的是复读前的 prompt");
    }

    @Test
    void leavesSystemOnlyPromptUntouched() {
        ChatClientRequest original = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new SystemMessage("SYSTEM"))))
                .build();

        List<Message> messages = rewrite(original).prompt().getInstructions();

        assertEquals(1, messages.size(), "纯 system 调用不应被塞入伪造的用户消息");
        assertEquals("SYSTEM", messages.get(0).getText());
    }

    @Test
    void isIdempotentAndLeavesOriginalPromptUntouched() {
        ChatClientRequest original = ChatClientRequest.builder().prompt(multiTurnPrompt()).build();

        String first = rewrite(original).prompt().getInstructions().get(3).getText();
        String second = rewrite(original).prompt().getInstructions().get(3).getText();

        assertEquals(first, second, "对同一请求反复改写不应叠加复读");
        assertEquals(CURRENT_QUESTION, original.prompt().getInstructions().get(3).getText(),
                "原请求不能被就地修改");
        assertSame(4, original.prompt().getInstructions().size());
    }
}
