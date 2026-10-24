package com.wgd.wgdaiagent.tool;

import com.wgd.wgdaiagent.agent.AgentProperties;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T6（规格 §9.3）：传输拼装全离线——只 build 传输对象，不 connect，不会发出任何请求。
 */
@Tag("offline")
class McpTransportFactoryTest {

    @Test
    void sseKeepsBaseAtHostAndPutsUrlEncodedKeyIntoEndpoint() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setBaseUrl("https://mcp.amap.com");
        properties.getMcp().setSsePath("/sse");
        properties.getMcp().setKey("a b");
        McpTransportFactory factory = new McpTransportFactory(properties);

        assertEquals("/sse?key=a+b", factory.sseEndpoint(),
                "query 必须拼在端点里（URI.resolve 会丢弃 base 上的 query），且 key 要 URL 编码");
        assertEquals("https://mcp.amap.com", properties.getMcp().getBaseUrl(), "base 只到 host");
        assertInstanceOf(HttpClientSseClientTransport.class, factory.create());
    }

    @Test
    void sseHonoursCustomBaseUrlAndPath() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setBaseUrl("https://proxy.example.com");
        properties.getMcp().setSsePath("/custom/sse");
        properties.getMcp().setKey("k+y");
        McpTransportFactory factory = new McpTransportFactory(properties);

        assertEquals("/custom/sse?key=k%2By", factory.sseEndpoint(), "key 必须整体 URL 编码，避免 query 被截断");
    }

    @Test
    void stdioUsesCmdOnWindowsAndPassesKeyThroughChildEnv() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setProtocol(AgentProperties.Mcp.Protocol.STDIO);
        properties.getMcp().setKey("a b");
        McpTransportFactory factory = new McpTransportFactory(properties);

        ServerParameters parameters = factory.serverParameters();

        assertEquals("cmd", parameters.getCommand(), "Windows 下直接跑 npx(.cmd) 会失败，必须经 cmd");
        assertEquals(List.of("/c", "npx", "-y", "@amap/amap-maps-mcp-server"), parameters.getArgs());
        assertEquals("a b", parameters.getEnv().get("AMAP_MAPS_API_KEY"), "凭据走子进程 env 的默认键名");
        // SDK 会把当前进程环境合并进 ServerParameters.env，所以这里只断言凭据键存在且值正确
        assertTrue(parameters.getEnv().containsKey("AMAP_MAPS_API_KEY"));
        assertInstanceOf(StdioClientTransport.class, factory.create());
    }

    @Test
    void stdioEnvKeyNameIsConfigurable() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setProtocol(AgentProperties.Mcp.Protocol.STDIO);
        properties.getMcp().setKey("dummy-key");
        properties.getMcp().getStdio().setEnvKeyName("MY_AMAP_KEY");
        McpTransportFactory factory = new McpTransportFactory(properties);

        ServerParameters parameters = factory.serverParameters();

        assertEquals("dummy-key", parameters.getEnv().get("MY_AMAP_KEY"));
        assertFalse(parameters.getEnv().containsKey("AMAP_MAPS_API_KEY"));
    }

    @Test
    void stdioArgsAndCommandFallBackToDefaults() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().getStdio().setCommand(null);
        properties.getMcp().getStdio().setArgs(List.of());
        properties.getMcp().setKey("dummy-key");
        McpTransportFactory factory = new McpTransportFactory(properties);

        ServerParameters parameters = factory.serverParameters();

        assertEquals("cmd", parameters.getCommand());
        assertEquals(List.of("/c", "npx", "-y", "@amap/amap-maps-mcp-server"), parameters.getArgs());
    }

    @Test
    void sseConnectTimeoutDefaultsToThreeSeconds() {
        AgentProperties properties = new AgentProperties();

        assertEquals(Duration.ofSeconds(3), properties.getMcp().getConnectTimeout());
        assertEquals(Duration.ofSeconds(20), properties.getMcp().getRequestTimeout());
        assertTrue(properties.getMcp().isEnabled(), "默认启用；无 key 时由 provider 降级");
    }

    /**
     * 离线守卫 §8.2 的配置块：yml 能解析、字段按规格、key 只有环境变量占位（AC8/§8.4）。
     * 默认门槛不启动 Spring 上下文，所以这里直接读 classpath 上的 application.yml。
     */
    @Test
    void applicationYmlDeclaresAgentBlockWithEnvPlaceholderKeyOnly() throws Exception {
        Map<String, Object> agent;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(input, "classpath 上应有 application.yml");
            agent = section(new Yaml().load(input), "love-app", "agent");
        }

        assertEquals(20, agent.get("history-window"));
        assertEquals(2000, agent.get("max-message-length"));
        assertEquals("60s", agent.get("sse-timeout"));

        Map<String, Object> mcp = section(agent, "mcp");
        assertEquals("${AMAP_MCP_ENABLED:true}", mcp.get("enabled"));
        assertEquals("${AMAP_MCP_PROTOCOL:sse}", mcp.get("protocol"));
        assertEquals("${AMAP_MCP_KEY:}", mcp.get("key"), "key 只能是环境变量占位，绝不写真实值");
        assertEquals("${AMAP_MCP_BASE_URL:https://mcp.amap.com}", mcp.get("base-url"));
        assertEquals("${AMAP_MCP_SSE_PATH:/sse}", mcp.get("sse-path"));
        assertEquals("3s", mcp.get("connect-timeout"));
        assertEquals("20s", mcp.get("request-timeout"));

        Map<String, Object> stdio = section(mcp, "stdio");
        assertEquals("cmd", stdio.get("command"));
        assertEquals(List.of("/c", "npx", "-y", "@amap/amap-maps-mcp-server"), stdio.get("args"));
        assertEquals("AMAP_MAPS_API_KEY", stdio.get("env-key-name"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String... path) {
        Map<String, Object> current = parent;
        for (String key : path) {
            Object next = current == null ? null : current.get(key);
            assertNotNull(next, "application.yml 缺少配置块：" + String.join(".", path));
            current = (Map<String, Object>) next;
        }
        return current;
    }
}
