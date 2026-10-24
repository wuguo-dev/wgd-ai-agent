package com.wgd.wgdaiagent.tool;

import com.wgd.wgdaiagent.agent.AgentProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 高德地图 MCP 工具源，<b>自管生命周期</b>（规格 §4.5 / §6.1 / §9.3 T4/T5）。
 *
 * <p>刻意不用 {@code spring-ai-starter-mcp-client} 的自动配置：它的
 * {@code McpClientAutoConfiguration} 默认 {@code initialized=true}，Bean 创建时即
 * {@code initialize()}，连不上直接启动失败；即使懒连接，只要走
 * {@code ReactAgent.builder().toolCallbackProviders(...)}，{@code build()} 时又会
 * {@code listTools()} 联网。只有自己 new 客户端、自己包 try/catch，才能保证
 * 「MCP 不可用也照常启动」（D7/D9）。</p>
 *
 * <p>降级语义：未启用 / key 为空 / 连接失败 / 超时 / {@code listTools()} 为空，
 * 一律 WARN + 返回空工具集，<b>绝不外抛</b>；日志只打原因与异常简单类名，不打 key、不打含 key 的 URL。</p>
 *
 * <p>连接是<b>首次取工具集时</b>建立一次并缓存（{@code LoveAgent} 启动期装配时会触发），
 * 不做热重连与后台探活（§11）。</p>
 */
@Slf4j
@Component
public class AmapMcpToolProvider {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final String STATUS_OK = "ok";

    private final AgentProperties properties;

    private final McpTransportFactory transportFactory;

    private volatile McpToolset toolset;

    public AmapMcpToolProvider(AgentProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transportFactory = new McpTransportFactory(properties);
    }

    /** 传输工厂的装配点，单独抽成方法便于离线用例替换（同包可见）。 */
    McpTransportFactory transportFactory() {
        return transportFactory;
    }

    /**
     * 取当前工具集；首次调用时才真正建连，之后复用同一结果。
     *
     * @return 永不为 {@code null}；不可用时 {@code available=false} 且 callbacks 为空
     */
    public McpToolset toolset() {
        McpToolset current = this.toolset;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = this.toolset;
            if (current == null) {
                current = connect();
                this.toolset = current;
            }
            return current;
        }
    }

    /**
     * 把「创建客户端」抽成独立方法，离线用例覆写它即可模拟 initialize/listTools 失败（§9.5）。
     * 包可见，避免把 MCP SDK 类型暴露到 tool 包外。
     */
    McpSyncClient createClient(McpClientTransport transport) {
        Duration requestTimeout = properties.getMcp().getRequestTimeout() == null
                ? DEFAULT_REQUEST_TIMEOUT
                : properties.getMcp().getRequestTimeout();
        return McpClient.sync(transport).requestTimeout(requestTimeout).build();
    }

    /** 连 → initialize → listTools → 包成 ToolCallback；任何失败只 WARN 并返回空工具集。 */
    private McpToolset connect() {
        String disabledReason = disabledReason();
        if (disabledReason != null) {
            log.warn("高德 MCP 未启用（原因：{}），仅本地工具可用", disabledReason);
            return new McpToolset(List.of(), false, disabledReason);
        }

        McpSyncClient client = null;
        try {
            // 可观测性：先打「实际走哪条协议、打到哪个路径」，排查时不用猜是 /mcp 还是 /sse。
            // 只含协议名、base host、路径（或 stdio 命令），绝不含 key 与完整 URL。
            log.info("MCP 传输：{}", transportDescription());
            McpClientTransport transport = transportFactory().create();
            client = createClient(transport);
            client.initialize();
            List<McpSchema.Tool> tools = listTools(client);
            if (tools.isEmpty()) {
                // §7.2：listTools() 返回空视作不可用
                log.warn("高德 MCP 未返回任何工具（listTools 为空），仅本地工具可用");
                closeQuietly(client);
                return new McpToolset(List.of(), false, "listTools 为空");
            }

            // D18：白名单过滤。工具名以 listTools() 返回的原始名为准，不写死任何名字
            List<String> excludeTools = excludeTools();
            List<McpSchema.Tool> exposedTools = new ArrayList<>(tools.size());
            List<String> excludedToolNames = new ArrayList<>();
            for (McpSchema.Tool tool : tools) {
                if (isExcluded(tool.name(), excludeTools)) {
                    excludedToolNames.add(tool.name());
                } else {
                    exposedTools.add(tool);
                }
            }
            if (exposedTools.isEmpty()) {
                // 全被排掉时不能留「available=true 但没有工具」的怪状态
                log.warn("高德 MCP 工具已全部被 exclude-tools 排除（原始 {} 个，排除 {} 个 {}），仅本地工具可用",
                        tools.size(), excludedToolNames.size(), excludedToolNames);
                closeQuietly(client);
                return new McpToolset(List.of(), false, "工具已全部被 exclude-tools 排除");
            }

            List<ToolCallback> callbacks = new ArrayList<>(exposedTools.size());
            for (McpSchema.Tool tool : exposedTools) {
                // 用非弃用的 builder：2 参构造器已 @Deprecated，且会拿 clientInfo.name 当前缀，
                // 让模型看到的工具名变成 <client>_<tool>。这里不加前缀，名字与 listTools() 一致（D16）。
                callbacks.add(SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(McpToolUtils.format(tool.name()))
                        .build());
            }
            log.info("高德 MCP 工具：原始 {} 个，暴露 {} 个，排除 {} 个{}",
                    tools.size(), callbacks.size(), excludedToolNames.size(),
                    excludedToolNames.isEmpty() ? "" : " " + excludedToolNames);
            // 工具名以运行时 listTools() 为准（D16），这里打的就是模型实际看到的名字
            log.info("高德 MCP 曝光工具 {} 个: {}",
                    callbacks.size(), callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
            return new McpToolset(callbacks, true, STATUS_OK);
        } catch (Exception e) {
            // 含超时与「HTTP 200 + JSON 错误体」：只打异常简单类名，消息里可能有含 key 的 URL
            log.warn("高德 MCP 不可用（{}），仅本地工具可用", e.getClass().getSimpleName());
            closeQuietly(client);
            return new McpToolset(List.of(), false, e.getClass().getSimpleName());
        }
    }

    private List<String> excludeTools() {        AgentProperties.Mcp mcp = properties.getMcp();
        return mcp == null || mcp.getExcludeTools() == null ? List.of() : mcp.getExcludeTools();
    }

    /**
     * 白名单匹配：entry 以 {@code *} 结尾 → 前缀匹配（{@code maps_schema_*} 命中
     * {@code maps_schema_navi}）；否则全名精确匹配（{@code maps_weather} 不命中
     * {@code maps_weather_extra}）。大小写敏感，空 entry 忽略。包可见，便于单测。
     */
    static boolean isExcluded(String toolName, List<String> excludeTools) {
        if (toolName == null || excludeTools == null || excludeTools.isEmpty()) {
            return false;
        }
        for (String entry : excludeTools) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String pattern = entry.trim();
            if (pattern.endsWith("*")) {
                if (toolName.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (toolName.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private List<McpSchema.Tool> listTools(McpSyncClient client) {
        McpSchema.ListToolsResult result = client.listTools();
        if (result == null || result.tools() == null) {
            return List.of();
        }
        return result.tools();
    }

    /**
     * 启动期「实际传输」的可观测描述，例：
     * {@code protocol=streamable-http，base=https://mcp.amap.com，path=/mcp}。
     *
     * <p>只由 protocol / baseUrl / streamablePath / ssePath / stdio.command 拼成，
     * <b>刻意不碰 {@code mcp.key}</b>，也不拼完整 URL（没有 {@code ?key=}）；stdio 下 base 记为 {@code -}
     * （stdio 不经过 HTTP base，打出来只会误导排查）。包可见，便于离线单测。</p>
     */
    String transportDescription() {
        AgentProperties.Mcp mcp = properties.getMcp();
        if (mcp == null) {
            return "protocol=<未配置>，base=-，path=-";
        }
        AgentProperties.Mcp.Protocol protocol = mcp.getProtocol() == null
                ? AgentProperties.Mcp.Protocol.STREAMABLE_HTTP
                : mcp.getProtocol();
        return switch (protocol) {
            case STREAMABLE_HTTP -> description("streamable-http", mcp.getBaseUrl(),
                    pathOrDefault(mcp.getStreamablePath(), "/mcp"));
            case SSE -> description("sse", mcp.getBaseUrl(), pathOrDefault(mcp.getSsePath(), "/sse"));
            case STDIO -> description("stdio", null, stdioCommand(mcp));
        };
    }

    private static String description(String protocol, String baseUrl, String target) {
        return "protocol=" + protocol
                + "，base=" + (StringUtils.hasText(baseUrl) ? baseUrl.trim() : "-")
                + "，path=" + target;
    }

    private static String pathOrDefault(String path, String fallback) {
        return StringUtils.hasText(path) ? path.trim() : fallback;
    }

    private static String stdioCommand(AgentProperties.Mcp mcp) {
        AgentProperties.Stdio stdio = mcp.getStdio();
        return stdio != null && StringUtils.hasText(stdio.getCommand()) ? stdio.getCommand().trim() : "cmd";
    }

    /** 返回不可用原因，可用时返回 {@code null}。 */
    private String disabledReason() {
        AgentProperties.Mcp mcp = properties.getMcp();
        if (mcp == null) {
            return "缺少 love-app.agent.mcp 配置";
        }
        if (!mcp.isEnabled()) {
            return "mcp.enabled=false";
        }
        if (!StringUtils.hasText(mcp.getKey())) {
            return "未配置 MCP key（环境变量 AMAP_MCP_KEY）";
        }
        return null;
    }

    private void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端时忽略异常：{}", e.getClass().getSimpleName());
        }
    }
}
