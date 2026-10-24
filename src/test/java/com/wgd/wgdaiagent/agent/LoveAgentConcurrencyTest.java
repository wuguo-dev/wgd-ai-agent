package com.wgd.wgdaiagent.agent;

import com.wgd.wgdaiagent.tool.LoveLocalTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.emptyMcpToolset;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.inMemoryProperties;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.mcpProvider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.provider;
import static com.wgd.wgdaiagent.agent.LoveAgentLoopTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18（规格 §9.3 / D4）：同一个 {@code LoveAgent} 并发调用不串台、不抛异常。
 *
 * <p>这里只覆盖内核的线程安全性；同一 chatId 的串行化由 service 层锁负责（T24，S4）。</p>
 */
@Tag("offline")
class LoveAgentConcurrencyTest {

    private static final int THREADS = 8;

    @Test
    void concurrentCallsWithDifferentChatIdsDoNotCrossTalk() throws Exception {
        LoveAgentLoopTest.ScriptedChatModel model = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> text("回答:" + lastUserText(prompt)));
        LoveAgent agent = newAgent(model);

        List<String> answers = runConcurrently(index -> agent.chat("chat-" + index, "问题" + index));

        for (int i = 0; i < THREADS; i++) {
            assertEquals("回答:问题" + i, answers.get(i), "第 " + i + " 个会话的答复必须只对应它自己的问题");
        }
    }

    @Test
    void concurrentCallsWithSameChatIdDoNotThrow() throws Exception {
        LoveAgentLoopTest.ScriptedChatModel model = new LoveAgentLoopTest.ScriptedChatModel(
                prompt -> text("回答:" + lastUserText(prompt)));
        LoveAgent agent = newAgent(model);

        List<String> answers = runConcurrently(index -> agent.chat("shared-chat", "并发问题" + index));

        assertEquals(THREADS, answers.size());
        answers.forEach(answer -> assertTrue(answer.startsWith("回答:并发问题"),
                "同一会话的并发调用必须都拿到答复，实际：" + answer));
    }

    // ---------- helpers ----------

    /** 全部线程先就位再一起放开，制造真正的并发。 */
    private static List<String> runConcurrently(IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>(THREADS);
            for (int i = 0; i < THREADS; i++) {
                int index = i;
                futures.add(pool.submit((Callable<String>) () -> {
                    ready.countDown();
                    start.await();
                    return call.apply(index);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "工作线程应全部就位");
            start.countDown();

            List<String> answers = new ArrayList<>(THREADS);
            for (Future<String> future : futures) {
                answers.add(future.get(30, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static LoveAgent newAgent(LoveAgentLoopTest.ScriptedChatModel model) {
        AgentProperties properties = inMemoryProperties();
        return new LoveAgent(model, properties, new PromptLoader(),
                mcpProvider(properties, emptyMcpToolset()), new LoveLocalTools(provider(null)));
    }

    private static String lastUserText(Prompt prompt) {
        List<UserMessage> userMessages = prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .toList();
        return userMessages.get(userMessages.size() - 1).getText();
    }

    @FunctionalInterface
    private interface IndexedCall {
        String apply(int index) throws Exception;
    }
}
