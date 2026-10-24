package com.wgd.wgdaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import lombok.extern.slf4j.Slf4j;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 基于文件持久化的对话记忆，<b>只服务 {@code LoveApp} 的旧 ChatClient 路径</b>。
 *
 * <p>agent 的会话记忆走 {@code FileSystemSaver}（{@code chat-memory/agent/}），与本类物理隔离，
 * 互不读取（规格 D10 / §4.4）。</p>
 *
 * <p>四处硬化（规格 §1.1 缺陷 3）：</p>
 * <ol>
 *   <li>{@link Kryo} 不再是 {@code static}：实例级字段 + 序列化时 {@code synchronized (kryo)}，
 *       避免并发写坏文件；</li>
 *   <li>「读-改-写」按 {@code conversationId} 粒度加锁，避免并发丢消息；</li>
 *   <li>原子写：先写 {@code .kryo.tmp}，再 {@code Files.move(ATOMIC_MOVE)} 覆盖，避免半截文件；</li>
 *   <li>会话 ID 白名单 {@code ^[A-Za-z0-9_-]{1,64}$}，不合法直接 {@link IllegalArgumentException}，
 *       堵住「ID 直接拼文件名」的路径穿越（R14）。</li>
 * </ol>
 *
 * <p>文件损坏（反序列化失败/内容非法）只 WARN 并按空历史返回，绝不把异常抛给对话链路。</p>
 */
@Slf4j
public class FileBasedChatMemory implements ChatMemory {

    /** 会话 ID 白名单（与 HTTP 层、agent 层同一份规则）。 */
    static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final String FILE_SUFFIX = ".kryo";

    private static final String TEMP_FILE_SUFFIX = ".kryo.tmp";

    private final Path baseDir;

    /** Kryo 本身不是线程安全的：每个实例一份，序列化/反序列化都锁在它上面。 */
    private final Kryo kryo;

    /** 按会话粒度的锁，保证同会话的「读-改-写」串行。 */
    private final ConcurrentHashMap<String, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();

    /** 构造对象时，指定文件保存目录。 */
    public FileBasedChatMemory(String dir) {
        this.baseDir = Path.of(dir);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话记忆目录: " + dir, e);
        }
        this.kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        // 设置实例化策略
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        validateConversationId(conversationId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 复制一份，避免调用方在锁外继续改同一个 List
        List<Message> appended = new ArrayList<>(messages);
        withLock(conversationId, () -> {
            List<Message> merged = readConversation(conversationId);
            merged.addAll(appended);
            writeConversation(conversationId, merged);
            return null;
        });
    }

    @Override
    public List<Message> get(String conversationId) {
        validateConversationId(conversationId);
        return withLock(conversationId, () -> readConversation(conversationId));
    }

    /**
     * 扩展方法：只取最近 lastN 条。
     * Spring AI 1.0+ 的 ChatMemory 接口已不再传 lastN（窗口裁剪交给 MessageWindowChatMemory），
     * 这里保留作为工具方法，需要时自行调用。
     */
    public List<Message> get(String conversationId, int lastN) {
        List<Message> allMessages = get(conversationId);
        return allMessages.stream()
                .skip(Math.max(0, allMessages.size() - lastN))
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        validateConversationId(conversationId);
        withLock(conversationId, () -> {
            deleteQuietly(conversationFile(conversationId));
            deleteQuietly(tempFile(conversationId));
            return null;
        });
    }

    // ---------- 读 / 写 ----------

    private List<Message> readConversation(String conversationId) {
        Path file = conversationFile(conversationId);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try (InputStream rawInput = Files.newInputStream(file);
             Input input = new Input(new BufferedInputStream(rawInput))) {
            Object deserialized;
            // Kryo 非线程安全
            synchronized (kryo) {
                deserialized = kryo.readObject(input, ArrayList.class);
            }
            List<Message> messages = toMessageList(deserialized);
            if (messages == null) {
                log.warn("会话记忆文件内容非法，按空历史处理: {}", file.getFileName());
                return new ArrayList<>();
            }
            return messages;
        } catch (IOException | RuntimeException e) {
            // 半截文件/格式变更/损坏：只 WARN，返回空历史，不打断对话
            log.warn("会话记忆文件损坏，按空历史处理（{}）: {}", e.getClass().getSimpleName(), file.getFileName());
            return new ArrayList<>();
        }
    }

    private void writeConversation(String conversationId, List<Message> messages) {
        Path target = conversationFile(conversationId);
        Path temp = tempFile(conversationId);
        try {
            Files.createDirectories(baseDir);
            List<Message> snapshot = new ArrayList<>(messages);
            try (OutputStream rawOutput = Files.newOutputStream(temp);
                 Output output = new Output(new BufferedOutputStream(rawOutput))) {
                synchronized (kryo) {
                    kryo.writeObject(output, snapshot);
                }
                output.flush();
            }
            // 原子替换：要么看到旧文件，要么看到完整的新文件，不会看到半截
            moveAtomically(temp, target);
        } catch (IOException | RuntimeException e) {
            log.warn("写会话记忆失败（{}）: {}", e.getClass().getSimpleName(), target.getFileName());
            deleteQuietly(temp);
        }
    }

    private static void moveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 某些文件系统（如容器挂载卷）不支持原子移动，退化为普通替换
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 只接受 {@code List<Message>}；任何其它内容都视为损坏。 */
    private static List<Message> toMessageList(Object deserialized) {
        if (!(deserialized instanceof List<?> rawList)) {
            return null;
        }
        List<Message> messages = new ArrayList<>(rawList.size());
        for (Object element : rawList) {
            if (!(element instanceof Message message)) {
                return null;
            }
            messages.add(message);
        }
        return messages;
    }

    // ---------- 路径与校验 ----------

    private Path conversationFile(String conversationId) {
        // 双保险：路径拼接前再校验一次（R14 路径穿越）
        validateConversationId(conversationId);
        return baseDir.resolve(conversationId + FILE_SUFFIX);
    }

    private Path tempFile(String conversationId) {
        validateConversationId(conversationId);
        return baseDir.resolve(conversationId + TEMP_FILE_SUFFIX);
    }

    private static String validateConversationId(String conversationId) {
        if (conversationId == null || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            // 不把原始 ID 写进异常/日志：它来自用户输入，可能含换行或注入片段
            throw new IllegalArgumentException("非法会话 ID：只允许 1-64 位字母、数字、下划线或短横线");
        }
        return conversationId;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除会话记忆文件失败（{}）: {}", e.getClass().getSimpleName(), path.getFileName());
        }
    }

    // ---------- 锁 ----------

    private <T> T withLock(String conversationId, Supplier<T> action) {
        ReentrantLock lock = conversationLocks.computeIfAbsent(conversationId, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
