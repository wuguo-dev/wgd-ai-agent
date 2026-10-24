package com.wgd.wgdaiagent.tool;

import com.wgd.wgdaiagent.agent.AgentProperties;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 按协议造 {@link McpClientTransport}（规格 §5.3 / §8.2 / §9.3 T6；W1 起默认 Streamable HTTP）。
 *
 * <p>三条硬约束：</p>
 * <ol>
 *   <li><b>Streamable HTTP（默认）</b>：base 只到 host，端点写 {@code /mcp?key=<URL 编码后的 key>}；
 *       且必须 {@code openConnectionOnStartup(false)}——构建传输时不建连，否则 MCP 不可达会拖垮启动（D7）。</li>
 *   <li><b>SSE（回退）</b>：同样 base 只到 host，{@code ?key=<URL 编码后的 key>} 必须拼在端点里。
 *       原因（与上条相同）：SDK 用 {@code Utils.resolveUri}（内部是 {@code URI.resolve}）拼地址，
 *       base 上带 query 会被丢弃；而 Spring AI 1.1.2 的 {@code SseParameters} 只有
 *       {@code url}/{@code sseEndpoint}，没有 per-connection header 配置。</li>
 *   <li><b>stdio（备选）</b>：Windows 下直接执行 {@code npx}(.cmd) 会失败，所以走 {@code cmd /c npx ...}，
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

    /** 默认协议（与 {@code AgentProperties.Mcp#protocol} 的默认值保持一致）。 */
    private static final AgentProperties.Mcp.Protocol DEFAULT_PROTOCOL =
            AgentProperties.Mcp.Protocol.STREAMABLE_HTTP;

    /** 按 {@code love-app.agent.mcp.protocol} 造传输；未配置时按 Streamable HTTP 处理（W1）。 */
    public McpClientTransport create() {
        AgentProperties.Mcp.Protocol protocol = mcp.getProtocol() == null ? DEFAULT_PROTOCOL : mcp.getProtocol();
        return switch (protocol) {
            case STDIO -> createStdio();
            case SSE -> createSse();
            case STREAMABLE_HTTP -> createStreamableHttp();
        };
    }

    /**
     * 高德 Streamable HTTP 端点（默认协议）：{@code https://mcp.amap.com} + {@code /mcp?key=...}。
     *
     * <p>{@code openConnectionOnStartup(false)} 是硬要求：构建传输时不建连，连不上也不会拖垮启动（D7），
     * 真正的连接由 {@code AmapMcpToolProvider} 在 try/catch 内主动 {@code initialize()} 触发。</p>
     */
    public McpClientTransport createStreamableHttp() {
        return HttpClientStreamableHttpTransport.builder(mcp.getBaseUrl())
                .endpoint(streamableEndpoint())
                .openConnectionOnStartup(false)
                .clientBuilder(HttpClient.newBuilder()
                        .connectTimeout(orDefault(mcp.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT)))
                .build();
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

    /**
     * Streamable HTTP 端点：{@code /mcp?key=<URL 编码后的 key>}。包可见，便于离线用例断言。
     *
     * <p>刻意不复用 {@link #sseEndpoint()}：两个协议路径不同（{@code /sse} vs {@code /mcp}），
     * 拼法相同（query 必须放端点，base 上的 query 会被 {@code URI.resolve} 丢弃）。</p>
     */
    String streamableEndpoint() {
        String path = StringUtils.hasText(mcp.getStreamablePath()) ? mcp.getStreamablePath() : "/mcp";
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
