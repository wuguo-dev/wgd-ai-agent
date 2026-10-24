package com.wgd.wgdaiagent.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T9（规格 §9.3 / D17）：上下文窗口裁剪规则。全离线，纯内存对象。
 */
@Tag("offline")
class HistoryWindowHookTest {

    private static final int WINDOW = 20;

    @Test
    void trimsToWindowKeepingUserMessageFirst() {
        List<Message> messages = conversation(30);
        HistoryWindowHook hook = new HistoryWindowHook(WINDOW);

        List<Message> trimmed = hook.trim(messages);

        assertEquals(WINDOW, trimmed.size(), "消息数超过窗口时裁到最近 window 条");
        assertInstanceOf(UserMessage.class, trimmed.get(0), "首条必须是一问的起点");
        assertEquals(messages.subList(messages.size() - WINDOW, messages.size()), trimmed, "保留的是最近 window 条");
    }

    @Test
    void keepsToolPairWhenWindowStartsInsideToolRound() {
        // 让窗口边界正好落在 ToolResponseMessage 上：index 24 对应 window=5 的起点
        List<Message> messages = conversation(30);
        HistoryWindowHook hook = new HistoryWindowHook(5);

        List<Message> trimmed = hook.trim(messages);

        assertInstanceOf(UserMessage.class, trimmed.get(0), "向前扩展到最近的一问");
        assertToolPairsIntact(trimmed);
        assertTrue(trimmed.size() >= 5, "为保持配对与一问起点，窗口可以更大");
    }

    @Test
    void returnsMessagesUnchangedWhenWithinWindow() {
        List<Message> messages = conversation(6);
        HistoryWindowHook hook = new HistoryWindowHook(WINDOW);

        assertEquals(messages, hook.trim(messages));
    }

    @Test
    void emptyInputStaysEmpty() {
        assertEquals(List.of(), new HistoryWindowHook(WINDOW).trim(List.of()));
    }

    @Test
    void beforeModelReplacesMessagesWithTrimmedList() {
        List<Message> messages = conversation(30);
        HistoryWindowHook hook = new HistoryWindowHook(WINDOW);
        RunnableConfig config = RunnableConfig.builder().threadId("chat-1").build();

        Map<String, Object> result = MessagesModelHook.beforeModelAction(hook)
                .apply(new OverAllState(Map.of("messages", new ArrayList<>(messages))), config)
                .join();

        Object replaced = result.get("messages");
        ReplaceAllWith<?> replaceAllWith = assertInstanceOf(ReplaceAllWith.class, replaced,
                "必须用 REPLACE 策略：追加会越裁越多");
        List<?> newValues = replaceAllWith.newValues();
        assertEquals(WINDOW, newValues.size());
        assertInstanceOf(UserMessage.class, newValues.get(0));
        assertToolPairsIntact(newValues.stream().map(Message.class::cast).toList());
    }

    // ---------- fixtures ----------

    /**
     * 造一段 30 条的真实形状会话：偶数位 User、奇数位 Assistant；
     * index 25/26 是一对「Assistant(toolCalls) + ToolResponse」。
     */
    private static List<Message> conversation(int size) {
        List<Message> messages = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (i == 25) {
                messages.add(toolCallMessage("call-25", "loveKbSearch", "{\"query\":\"异地恋\"}"));
            } else if (i == 26) {
                messages.add(toolResponseMessage("call-25"));
            } else if (i % 2 == 0) {
                messages.add(new UserMessage("问题" + i));
            } else {
                messages.add(new AssistantMessage("回答" + i));
            }
        }
        return messages;
    }

    private static void assertToolPairsIntact(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                assertTrue(i > 0, "ToolResponseMessage 不能是首条");
                Message previous = messages.get(i - 1);
                boolean paired = previous instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls();
                assertTrue(paired, "ToolResponseMessage 前必须是携带 toolCalls 的 AssistantMessage，实际：" + previous);
            }
        }
    }

    private static AssistantMessage toolCallMessage(String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
    }

    private static ToolResponseMessage toolResponseMessage(String id) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "loveKbSearch", "结果")))
                .build();
    }
}
