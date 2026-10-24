package com.wgd.wgdaiagent.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 恋爱大师 Agent 的配置绑定（前缀 {@code love-app.agent}，规格 §8.1）。
 *
 * <p>只做绑定，不发请求。刻意不使用 Lombok 的 {@code @Data}：它生成的 {@code toString()}
 * 会把 {@code mcp.key} 打出去，与 §8.4「日志不得包含 key」冲突。</p>
 */
@Component
@ConfigurationProperties(prefix = "love-app.agent")
@Getter
@Setter
public class AgentProperties {

    /** {@code HistoryWindowHook} 每次模型调用前保留的消息条数。 */
    private int historyWindow = 20;

    /** 单条用户消息长度上限（HTTP 层校验用）。 */
    private int maxMessageLength = 2000;

    /** {@code SseEmitter} 超时。 */
    private Duration sseTimeout = Duration.ofSeconds(60);

    /**
     * 是否打开框架的推理日志（{@code ReactAgent.builder().enableLogging(...)}），<b>默认关闭</b>。
     *
     * <p>打开后 {@code AgentLlmNode} 会在 INFO 打印 system prompt、工具入参与答复全文，
     * 与 §7.4「INFO/WARN 不打正文」冲突，仅供本地排障临时开启（环境变量
     * {@code LOVE_AGENT_LOG_REASONING=true}）。</p>
     */
    private boolean logReasoning = false;

    /** 会话记忆（checkpoint saver）配置。 */
    private Memory memory = new Memory();

    /** 高德 MCP 客户端配置。 */
    private Mcp mcp = new Mcp();

    /** 记忆实现：{@code file}=FileSystemSaver，{@code memory}=MemorySaver。 */
    @Getter
    @Setter
    public static class Memory {

        private Type type = Type.FILE;

        /** 相对 {@code user.dir} 的检查点目录。 */
        private String dir = "chat-memory/agent";

        public enum Type {
            FILE,
            MEMORY
        }
    }

    /** 高德 MCP 客户端配置；{@code key} 只能来自环境变量，不得有默认明文。 */
    @Getter
    @Setter
    public static class Mcp {

        private boolean enabled = true;

        private Protocol protocol = Protocol.SSE;

        /** 唯一凭据来源（环境变量 {@code AMAP_MCP_KEY}）；为空即降级为「仅本地工具」。 */
        private String key = "";

        /** 只到 host，不含 path/query（{@code Utils.resolveUri} 会丢弃 base 上的 query）。 */
        private String baseUrl = "https://mcp.amap.com";

        /** 代码负责拼 {@code ?key=}，所以默认值不带 query。 */
        private String ssePath = "/sse";

        private Duration connectTimeout = Duration.ofSeconds(3);

        private Duration requestTimeout = Duration.ofSeconds(20);

        private Stdio stdio = new Stdio();

        public enum Protocol {
            SSE,
            STDIO
        }
    }

    /** stdio 备选传输：Windows 下直接执行 npx(.cmd) 会失败，所以经 {@code cmd /c} 启动。 */
    @Getter
    @Setter
    public static class Stdio {

        private String command = "cmd";

        private List<String> args = List.of("/c", "npx", "-y", "@amap/amap-maps-mcp-server");

        /** 传给子进程的环境变量名（npm 包约定）。 */
        private String envKeyName = "AMAP_MAPS_API_KEY";
    }
}
