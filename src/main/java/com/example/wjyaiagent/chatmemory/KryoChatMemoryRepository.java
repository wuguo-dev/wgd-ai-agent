package com.example.wjyaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Kryo-backed repository storing one conversation per file. */
public class KryoChatMemoryRepository implements ChatMemoryRepository {

    private final Path directory;

    public KryoChatMemoryRepository(String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException error) {
            throw new IllegalStateException("无法创建对话记忆目录", error);
        }
    }

    @Override
    public synchronized List<String> findConversationIds() {
        try {
            return Files.list(directory)
                    .filter(path -> path.getFileName().toString().endsWith(".kryo"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.kryo$", ""))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取对话记忆目录", error);
        }
    }

    @Override
    public synchronized List<Message> findByConversationId(String conversationId) {
        Path file = fileFor(conversationId);
        if (!Files.exists(file)) return List.of();
        try (InputStream inputStream = Files.newInputStream(file);
             Input input = new Input(inputStream)) {
            @SuppressWarnings("unchecked")
            List<StoredMessage> storedMessages = newKryo().readObject(input, ArrayList.class);
            return storedMessages.stream().map(KryoChatMemoryRepository::restore).toList();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取对话记忆文件", error);
        }
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        List<StoredMessage> storedMessages = new ArrayList<>(messages.size());
        for (Message message : messages) {
            String text = message.getText();
            if (text != null && message.getMessageType() != MessageType.TOOL) {
                storedMessages.add(new StoredMessage(message.getMessageType().getValue(), text));
            }
        }
        write(fileFor(conversationId), storedMessages);
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        try {
            Files.deleteIfExists(fileFor(conversationId));
        } catch (IOException error) {
            throw new IllegalStateException("无法删除对话记忆文件", error);
        }
    }

    private void write(Path file, List<StoredMessage> messages) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             Output output = new Output(outputStream)) {
            newKryo().writeObject(output, messages);
        } catch (IOException error) {
            throw new IllegalStateException("无法写入对话记忆文件", error);
        }
        try {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法替换对话记忆文件", error);
        }
    }

    private Path fileFor(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        String fileName = conversationId.matches("[A-Za-z0-9_-]{1,128}")
                ? conversationId : hash(conversationId);
        return directory.resolve(fileName + ".kryo");
    }

    private static Message restore(StoredMessage message) {
        return switch (message.type) {
            case "USER" -> new UserMessage(message.text);
            case "ASSISTANT" -> new AssistantMessage(message.text);
            case "SYSTEM" -> new SystemMessage(message.text);
            default -> new UserMessage(message.text);
        };
    }

    private static Kryo newKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        return kryo;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算对话记忆文件名", error);
        }
    }

    public static class StoredMessage {
        public String type;
        public String text;

        public StoredMessage() {
        }

        StoredMessage(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}
