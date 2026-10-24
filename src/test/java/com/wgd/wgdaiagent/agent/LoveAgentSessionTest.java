package com.wgd.wgdaiagent.agent;

import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;

import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.emptyMcpToolset;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.mcpProvider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.provider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T16/T17（规格 §9.3 / D10）：saver 记忆跨请求、跨实例可恢复，不同 chatId 互不串台。
 *
 * <p>目录一律 {@link TempDir}，不碰仓库根的 {@code chat-memory/}。</p>
 */
@Tag("offline")
class LoveAgentSessionTest {

    @Test
    void historyIsRestoredWithinInstanceAndAcrossRestart(@TempDir Path memoryDir) {
        AgentProperties properties = fileProperties(memoryDir);
        LoveAgentLoopTest.ScriptedChatModel model = echoingModel();
        LoveAgent agent = newAgent(properties, model);

        agent.chat("chat-1", "第一轮问题");
        agent.chat("chat-1", "第二轮问题");

        int firstRoundSize = model.prompts.get(0).getInstructions().size();
        int secondRoundSize = model.prompts.get(1).getInstructions().size();
        assertTrue(secondRoundSize > firstRoundSize,
                "第二轮必须能看到第一轮历史：第一轮 " + firstRoundSize + " 条，第二轮 " + secondRoundSize + " 条");

        // 用同一目录新建实例 = 进程重启后恢复（HISTORY 在磁盘上）
        LoveAgent restarted = newAgent(properties, model);
        restarted.chat("chat-1", "第三轮问题");

        int thirdRoundSize = model.prompts.get(2).getInstructions().size();
        assertTrue(thirdRoundSize > secondRoundSize,
                "重启后历史仍在：第二轮 " + secondRoundSize + " 条，第三轮 " + thirdRoundSize + " 条");
        assertTrue(model.prompts.get(2).getInstructions().stream()
                        .anyMatch(message -> "第一轮问题".equals(message.getText())),
                "重启后仍能引用第一轮内容");
    }

    @Test
    void differentChatIdsDoNotShareHistory(@TempDir Path memoryDir) {
        AgentProperties properties = fileProperties(memoryDir);
        LoveAgentLoopTest.ScriptedChatModel model = echoingModel();
        LoveAgent agent = newAgent(properties, model);

        String answerA = agent.chat("chat-a", "A 的问题");
        String answerB = agent.chat("chat-b", "B 的问题");

        assertEquals("回答:A 的问题", answerA);
        assertEquals("回答:B 的问题", answerB, "两个会话各自只看到自己的历史");
        Prompt promptForB = model.prompts.get(1);
        assertFalse(promptForB.getInstructions().stream().anyMatch(message -> "A 的问题".equals(message.getText())),
                "chat-b 不应看到 chat-a 的历史");
        assertEquals(1, userMessages(promptForB).size(), "chat-b 的第一次请求只有本轮一条用户消息");
    }

    @Test
    void invalidChatIdIsRejected(@TempDir Path memoryDir) {
        AgentProperties properties = fileProperties(memoryDir);
        LoveAgent agent = newAgent(properties, echoingModel());

        LoveAgentException traversal = assertThrows(LoveAgentException.class,
                () -> agent.chat("../../etc/passwd", "试图路径穿越"));
        assertEquals(AgentErrorCode.INVALID_REQUEST, traversal.getCode());

        LoveAgentException blank = assertThrows(LoveAgentException.class, () -> agent.chat("  ", "空 id"));
        assertEquals(AgentErrorCode.INVALID_REQUEST, blank.getCode());

        LoveAgentException tooLong = assertThrows(LoveAgentException.class,
                () -> agent.chat("a".repeat(65), "过长 id"));
        assertEquals(AgentErrorCode.INVALID_REQUEST, tooLong.getCode());
    }

    // ---------- helpers ----------

    private static LoveAgentLoopTest.ScriptedChatModel echoingModel() {
        return new LoveAgentLoopTest.ScriptedChatModel(prompt -> text("回答:" + lastUserText(prompt)));
    }

    private static LoveAgent newAgent(AgentProperties properties, LoveAgentLoopTest.ScriptedChatModel model) {
        return new LoveAgent(model, properties, new PromptLoader(),
                mcpProvider(properties, emptyMcpToolset()), new LoveLocalTools(provider(null)));
    }

    private static AgentProperties fileProperties(Path memoryDir) {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setType(AgentProperties.Memory.Type.FILE);
        properties.getMemory().setDir(memoryDir.toString());
        return properties;
    }

    private static String lastUserText(Prompt prompt) {
        return userMessages(prompt).get(userMessages(prompt).size() - 1).getText();
    }

    private static List<? extends Message> userMessages(Prompt prompt) {
        return prompt.getInstructions().stream().filter(UserMessage.class::isInstance).toList();
    }
}
