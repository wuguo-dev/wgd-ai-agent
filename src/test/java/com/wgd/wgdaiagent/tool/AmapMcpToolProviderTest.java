package com.wgd.wgdaiagent.tool;

import com.wgd.wgdaiagent.agent.AgentProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4/T5（规格 §9.3）：MCP 工具源自管生命周期的降级语义，全程不联网。
 *
 * <p>「不建连接」用可观测的 {@link McpTransportFactory} 子类断言：被调用就置位并抛异常，
 * 一旦降级路径误建连接，用例立即失败。</p>
 */
@Tag("offline")
class AmapMcpToolProviderTest {

    private static final String DUMMY_KEY = "offline-dummy-key";

    // ---------- T4 未启用 ----------

    @Test
    void missingKeyReturnsEmptyToolsetWithoutConnecting() {
        AgentProperties properties = properties(DUMMY_KEY);
        properties.getMcp().setKey("");
        RecordingTransportFactory factory = new RecordingTransportFactory(properties);

        McpToolset toolset = provider(properties, factory).toolset();

        assertFalse(toolset.available());
        assertTrue(toolset.callbacks().isEmpty());
        assertFalse(factory.called, "key 为空时不应创建任何传输");
        assertEquals("未配置 MCP key（环境变量 AMAP_MCP_KEY）", toolset.status());
    }

    @Test
    void blankKeyReturnsEmptyToolsetWithoutConnecting() {
        AgentProperties properties = properties("   ");
        RecordingTransportFactory factory = new RecordingTransportFactory(properties);

        McpToolset toolset = provider(properties, factory).toolset();

        assertFalse(toolset.available());
        assertTrue(toolset.callbacks().isEmpty());
        assertFalse(factory.called);
    }

    @Test
    void disabledFlagReturnsEmptyToolsetWithoutConnecting() {
        AgentProperties properties = properties(DUMMY_KEY);
        properties.getMcp().setEnabled(false);
        RecordingTransportFactory factory = new RecordingTransportFactory(properties);

        McpToolset toolset = provider(properties, factory).toolset();

        assertFalse(toolset.available());
        assertTrue(toolset.callbacks().isEmpty());
        assertFalse(factory.called, "enabled=false 时不应创建任何传输");
        assertEquals("mcp.enabled=false", toolset.status());
    }

    // ---------- T5 连上了但初始化失败 ----------

    @Test
    void initializeFailureIsCaughtAndClientIsClosed() {
        AgentProperties properties = properties(DUMMY_KEY);
        McpSyncClient stub = mock(McpSyncClient.class);
        doThrow(new IllegalStateException("INVALID_USER_KEY")).when(stub).initialize();

        McpToolset toolset = provider(properties, stub).toolset();

        assertFalse(toolset.available());
        assertTrue(toolset.callbacks().isEmpty());
        assertEquals("IllegalStateException", toolset.status(), "status 只放异常简单类名，不放 message");
        verify(stub).close();
    }

    @Test
    void listToolsFailureIsCaughtAndClientIsClosed() {
        AgentProperties properties = properties(DUMMY_KEY);
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenThrow(new RuntimeException("boom"));

        McpToolset toolset = provider(properties, stub).toolset();

        assertFalse(toolset.available());
        assertTrue(toolset.callbacks().isEmpty());
        verify(stub).close();
    }

    @Test
    void emptyToolListIsTreatedAsUnavailable() {
        AgentProperties properties = properties(DUMMY_KEY);
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));

        McpToolset toolset = provider(properties, stub).toolset();

        assertFalse(toolset.available());
        assertTrue(toolset.callbacks().isEmpty());
        verify(stub).close();
    }

    @Test
    void successfulListToolsIsWrappedIntoToolCallbacks() {
        AgentProperties properties = properties(DUMMY_KEY);
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(
                List.of(tool("maps_weather"), tool("maps_text_search")), null));

        McpToolset toolset = provider(properties, stub).toolset();

        assertTrue(toolset.available());
        assertEquals("ok", toolset.status());
        assertEquals(List.of("maps_weather", "maps_text_search"),
                toolset.callbacks().stream().map(callback -> callback.getToolDefinition().name()).toList(),
                "工具名以 listTools() 返回的原名为准，不额外加客户端前缀");
    }

    @Test
    void toolsetIsResolvedOnlyOnce() {
        AgentProperties properties = properties(DUMMY_KEY);
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("maps_weather")), null));
        AmapMcpToolProvider provider = provider(properties, stub);

        provider.toolset();
        provider.toolset();

        verify(stub).initialize();
        verify(stub).listTools();
    }

    // ---------- 辅助 ----------

    private static AmapMcpToolProvider provider(AgentProperties properties, McpSyncClient stub) {
        return new AmapMcpToolProvider(properties) {
            @Override
            McpSyncClient createClient(McpClientTransport transport) {
                return stub;
            }
        };
    }

    /** 注入可观测的传输工厂：降级路径不该调用它。 */
    private static AmapMcpToolProvider provider(AgentProperties properties, RecordingTransportFactory factory) {
        return new AmapMcpToolProvider(properties) {
            @Override
            McpTransportFactory transportFactory() {
                return factory;
            }
        };
    }

    private static AgentProperties properties(String key) {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setKey(key);
        return properties;
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool.Builder()
                .name(name)
                .description(name + " 的描述")
                .inputSchema(McpJsonMapper.getDefault(), "{\"type\":\"object\",\"properties\":{}}")
                .build();
    }

    /** 可观测的传输工厂：降级路径不该调用它。 */
    private static final class RecordingTransportFactory extends McpTransportFactory {

        private boolean called;

        private RecordingTransportFactory(AgentProperties properties) {
            super(properties);
        }

        @Override
        public McpClientTransport create() {
            this.called = true;
            throw new IllegalStateException("降级路径不应创建传输");
        }
    }
}
