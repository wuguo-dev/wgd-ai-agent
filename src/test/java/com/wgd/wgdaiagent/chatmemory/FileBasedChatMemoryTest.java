package com.wgd.wgdaiagent.chatmemory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T19/T20（规格 §9.3 / D10 / R13 / R14）：旧 ChatClient 路径的文件记忆硬化。
 *
 * <p>磁盘只用 {@link TempDir}，不碰仓库根的 {@code chat-memory/}。</p>
 */
@Tag("offline")
class FileBasedChatMemoryTest {

    private static final String CONVERSATION_ID = "chat-concurrent";

    // ---------- T19 ----------

    @Test
    void concurrentAddsDoNotLoseMessages(@TempDir Path dir) throws Exception {
        FileBasedChatMemory memory = new FileBasedChatMemory(dir.toString());
        int threads = 10;
        int perThread = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int threadIndex = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        memory.add(CONVERSATION_ID, List.of(new UserMessage("线程" + threadIndex + "-" + j)));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Message> messages = memory.get(CONVERSATION_ID);
        assertEquals(threads * perThread, messages.size(), "10 线程 × 10 次 add 后必须一条不丢");
        assertEquals(threads * perThread, messages.stream().map(Message::getText).distinct().count(),
                "内容也不能重复/丢失");
    }

    @Test
    void newInstanceReadsPersistedHistory(@TempDir Path dir) {
        FileBasedChatMemory first = new FileBasedChatMemory(dir.toString());
        first.add("chat-1", List.of(new UserMessage("异地恋怎么维持"), new AssistantMessage("保持固定沟通节奏")));

        // 新实例 = 进程重启
        FileBasedChatMemory second = new FileBasedChatMemory(dir.toString());

        List<Message> messages = second.get("chat-1");
        assertEquals(2, messages.size(), "重启后历史应能读回");
        assertEquals("异地恋怎么维持", messages.get(0).getText());
        assertEquals("保持固定沟通节奏", messages.get(1).getText());
        assertTrue(Files.exists(dir.resolve("chat-1.kryo")));
        assertFalse(Files.exists(dir.resolve("chat-1.kryo.tmp")), "临时文件应已被原子替换掉");
    }

    @Test
    void corruptedFileReturnsEmptyHistoryWithoutThrowing(@TempDir Path dir) throws Exception {
        FileBasedChatMemory memory = new FileBasedChatMemory(dir.toString());
        memory.add("chat-1", List.of(new UserMessage("即将被写坏")));
        Files.writeString(dir.resolve("chat-1.kryo"), "这不是一个 kryo 文件", StandardCharsets.UTF_8);

        List<Message> messages = assertDoesNotThrow(() -> memory.get("chat-1"),
                "损坏文件不能把异常抛给对话链路");

        assertTrue(messages.isEmpty(), "损坏文件按空历史处理");
    }

    // ---------- T20 ----------

    @Test
    void rejectsPathTraversalAndIllegalIds(@TempDir Path dir) {
        FileBasedChatMemory memory = new FileBasedChatMemory(dir.toString());

        assertThrows(IllegalArgumentException.class, () -> memory.get("../../etc/passwd"), "路径穿越必须被拒绝");
        assertThrows(IllegalArgumentException.class, () -> memory.add("../x", List.of(new UserMessage("x"))));
        assertThrows(IllegalArgumentException.class, () -> memory.clear("a/b"));
        assertThrows(IllegalArgumentException.class, () -> memory.get(""));
        assertThrows(IllegalArgumentException.class, () -> memory.get("a".repeat(65)), "超过 64 位必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> memory.get(null));
    }

    @Test
    void acceptsLegalIdAndClearsFile(@TempDir Path dir) {
        FileBasedChatMemory memory = new FileBasedChatMemory(dir.toString());

        memory.add("chat_id-1", List.of(new UserMessage("你好")));
        assertTrue(Files.exists(dir.resolve("chat_id-1.kryo")), "合法 ID（含下划线与短横线）应能落盘");
        assertEquals(1, memory.get("chat_id-1").size());

        memory.clear("chat_id-1");

        assertFalse(Files.exists(dir.resolve("chat_id-1.kryo")), "clear() 后文件必须不存在");
        assertTrue(memory.get("chat_id-1").isEmpty());
    }

    @Test
    void lastNReturnsTrailingMessages(@TempDir Path dir) {
        FileBasedChatMemory memory = new FileBasedChatMemory(dir.toString());
        memory.add("chat-1", List.of(new UserMessage("一"), new AssistantMessage("二"), new UserMessage("三")));

        List<Message> lastTwo = memory.get("chat-1", 2);

        assertEquals(2, lastTwo.size());
        assertEquals("二", lastTwo.get(0).getText());
        assertEquals("三", lastTwo.get(1).getText());
    }
}
