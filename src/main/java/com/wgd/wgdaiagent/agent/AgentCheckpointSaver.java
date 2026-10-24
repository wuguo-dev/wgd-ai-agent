package com.wgd.wgdaiagent.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 构造会话记忆的 checkpoint saver（规格 D10 / §4.4 / §4.5）。
 *
 * <p>{@code memory.type=file} → {@link FileSystemSaver}，落在 {@code memory.dir}
 * （相对路径按 {@code user.dir} 解析，默认 {@code chat-memory/agent}）；
 * {@code memory.type=memory} → {@link MemorySaver}。构建失败只 WARN 并降级为
 * {@link MemorySaver}，不阻断启动。</p>
 *
 * <p>序列化器用 {@link SpringAIJacksonStateSerializer}，与 {@code ReactAgent} 的默认状态序列化器
 * 保持一致（{@code ReactAgent} 内部就是 {@code new SpringAIJacksonStateSerializer(OverAllState::new)}）；
 * 用另一套 std 序列化器存同一批 checkpoint 属于混用，读写类型容易错配。</p>
 */
@Slf4j
public final class AgentCheckpointSaver {

    static final String DEFAULT_DIR = "chat-memory/agent";

    private AgentCheckpointSaver() {
    }

    /** 按配置造 saver；永远返回可用实例（最差是内存版）。 */
    public static BaseCheckpointSaver create(AgentProperties.Memory memory) {
        AgentProperties.Memory config = memory == null ? new AgentProperties.Memory() : memory;
        if (config.getType() == AgentProperties.Memory.Type.MEMORY) {
            log.info("agent 记忆使用内存 saver（重启丢失）");
            return new MemorySaver();
        }

        Path targetFolder = resolveDir(config.getDir());
        try {
            BaseCheckpointSaver saver = FileSystemSaver.builder()
                    .targetFolder(targetFolder)
                    .stateSerializer(newStateSerializer())
                    .build();
            log.info("agent 记忆使用文件 saver，目录={}", targetFolder);
            return saver;
        } catch (RuntimeException e) {
            // 目录不可建/不可写都走这里：进程内仍可多轮对话
            log.warn("文件 saver 初始化失败（{}），降级为内存 saver（重启会丢历史）", e.getClass().getSimpleName());
            return new MemorySaver();
        }
    }

    /** 相对路径按 {@code user.dir} 解析；绝对路径原样使用（离线用例传 {@code @TempDir}）。 */
    static Path resolveDir(String dir) {
        String configured = StringUtils.hasText(dir) ? dir : DEFAULT_DIR;
        Path path = Path.of(configured);
        return path.isAbsolute() ? path : Path.of(System.getProperty("user.dir")).resolve(configured);
    }

    private static StateSerializer newStateSerializer() {
        return new SpringAIJacksonStateSerializer(OverAllState::new);
    }
}
