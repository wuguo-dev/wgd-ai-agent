package com.wgd.wgdaiagent.tool;

import com.wgd.wgdaiagent.agent.AgentProperties;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 按协议造 {@link McpClientTransport}（规格 §5.3 / §8.2 / §9.3 T6）。
 *
 * <p>两条硬约束：</p>
 * <ol>
 *   <li><b>SSE</b>：base 只到 host，{@code ?key=<URL 编码后的 key>} 必须拼在端点里。
 *       原因：SDK 用 {@code Utils.resolveUri}（内部是 {@code URI.resolve}）拼地址，
 *       base 上带 query 会被丢弃；而 Spring AI 1.1.2 的 {@code SseParameters} 只有
 *       {@code url}/{@code sseEndpoint}，没有 per-connection header 配置。</li>
 *   <li><b>stdio</b>：Windows 下直接执行 {@code npx}(.cmd) 会失败，所以走 {@code cmd /c npx ...}，
 *       凭据通过子进程环境变量传递（键名可配，默认 {@code AMAP_MAPS_API_KEY}）。</li>
 * </ol>
 *
 * <p>只负责造传输，不负责连接、重试、探活。日志里绝不出现 key 或含 key 的 URL。</p>
 */
@Slf4j
public class McpTransportFactory {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final List<String> DEFAULT_STDIO_ARGS =
            List.of("/c", "npx", "-y", "@amap/amap-maps-mcp-server");

    private final AgentProperties.Mcp mcp;

    public McpTransportFactory(AgentProperties properties) {
        this.mcp = Objects.requireNonNull(Objects.requireNonNull(properties, "properties").getMcp(), "mcp");
    }

    /** 按 {@code love-app.agent.mcp.protocol} 造传输；未配置时按 SSE 处理。 */
    public McpClientTransport create() {
        AgentProperties.Mcp.Protocol protocol =
                mcp.getProtocol() == null ? AgentProperties.Mcp.Protocol.SSE : mcp.getProtocol();
        return switch (protocol) {
            case STDIO -> createStdio();
            case SSE -> createSse();
        };
    }

    /** 高德托管端点：{@code https://mcp.amap.com} + {@code /sse?key=...}。 */
    public McpClientTransport createSse() {
        return HttpClientSseClientTransport.builder(mcp.getBaseUrl())
                .sseEndpoint(sseEndpoint())
                .connectTimeout(orDefault(mcp.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT))
                .build();
    }

    /** npm 备选：{@code cmd /c npx -y @amap/amap-maps-mcp-server}，key 走子进程 env。 */
    public McpClientTransport createStdio() {
        return new StdioClientTransport(serverParameters(), McpJsonMapper.getDefault());
    }

    /**
     * SSE 端点（只含 path 与 query，base 见 {@code mcp.base-url}）：
     * {@code /sse?key=<URL 编码后的 key>}。包可见，便于离线用例直接断言拼接结果。
     */
    String sseEndpoint() {
        String path = StringUtils.hasText(mcp.getSsePath()) ? mcp.getSsePath() : "/sse";
        return path + "?key=" + URLEncoder.encode(nullToEmpty(mcp.getKey()), StandardCharsets.UTF_8);
    }

    /** stdio 子进程参数：command、args、env（key 非空时才注入）。包可见，便于用例断言。 */
    ServerParameters serverParameters() {
        AgentProperties.Stdio stdio = mcp.getStdio();
        List<String> args = stdio != null && stdio.getArgs() != null && !stdio.getArgs().isEmpty()
                ? stdio.getArgs()
                : DEFAULT_STDIO_ARGS;
        String command = stdio != null && StringUtils.hasText(stdio.getCommand())
                ? stdio.getCommand()
                : "cmd";

        ServerParameters.Builder builder = ServerParameters.builder(command).args(args);
        if (stdio != null && StringUtils.hasText(stdio.getEnvKeyName()) && StringUtils.hasText(mcp.getKey())) {
            builder.addEnvVar(stdio.getEnvKeyName(), mcp.getKey());
        }
        return builder.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
