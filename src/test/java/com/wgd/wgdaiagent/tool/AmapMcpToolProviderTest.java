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

    // ---------- D18 工具白名单 ----------

    @Test
    void defaultExcludesSchemaMetaToolsFromFifteenToolEndpoint() {
        AgentProperties properties = properties(DUMMY_KEY);
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(fifteenTools(), null));

        McpToolset toolset = provider(properties, stub).toolset();

        assertTrue(toolset.available(), "排除后仍有 12 个工具，应可用");
        List<String> exposed = toolset.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
        assertEquals(12, exposed.size(), "实测托管端点 15 个工具，默认应暴露 12 个：" + exposed);
        assertTrue(exposed.stream().noneMatch(name -> name.startsWith("maps_schema_")),
                "3 个 schema 元工具必须被默认排除：" + exposed);
        assertTrue(exposed.contains("maps_direction_bicycling"), "以运行时实际工具名为准，不按旧名写死：" + exposed);
    }

    @Test
    void exactToolNameExclusionTakesEffect() {
        AgentProperties properties = properties(DUMMY_KEY);
        properties.getMcp().setExcludeTools(List.of("maps_weather"));
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(fifteenTools(), null));

        List<String> exposed = provider(properties, stub).toolset().callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertFalse(exposed.contains("maps_weather"), "精确名应被排除：" + exposed);
        assertTrue(exposed.contains("maps_text_search"), "其它工具不受影响：" + exposed);
        assertEquals(14, exposed.size(),
                "配置是「整体覆盖」默认值：只配 maps_weather 时 3 个 schema 元工具会重新暴露（生默认值以代码为准）：" + exposed);
    }

    @Test
    void excludingEverythingDegradesToUnavailable() {
        AgentProperties properties = properties(DUMMY_KEY);
        properties.getMcp().setExcludeTools(List.of("*"));
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(fifteenTools(), null));

        McpToolset toolset = provider(properties, stub).toolset();

        assertFalse(toolset.available(), "全被排除时不能留 available=true 但没有工具的怪状态");
        assertTrue(toolset.callbacks().isEmpty());
        assertEquals("工具已全部被 exclude-tools 排除", toolset.status());
        verify(stub).close();
    }

    @Test
    void emptyExcludeListExposesEveryTool() {
        AgentProperties properties = properties(DUMMY_KEY);
        properties.getMcp().setExcludeTools(List.of());
        McpSyncClient stub = mock(McpSyncClient.class);
        when(stub.listTools()).thenReturn(new McpSchema.ListToolsResult(fifteenTools(), null));

        McpToolset toolset = provider(properties, stub).toolset();

        assertEquals(15, toolset.callbacks().size(), "显式清空白名单应恢复全量暴露");
        assertTrue(toolset.available());
    }

    @Test
    void exclusionMatcherSupportsPrefixWildcardAndExactName() {
        assertTrue(AmapMcpToolProvider.isExcluded("maps_schema_navi", List.of("maps_schema_*")));
        assertTrue(AmapMcpToolProvider.isExcluded("maps_schema_take_taxi", List.of("maps_schema_*")));
        assertFalse(AmapMcpToolProvider.isExcluded("maps_weather", List.of("maps_schema_*")));
        assertTrue(AmapMcpToolProvider.isExcluded("maps_weather", List.of("maps_weather")));
        assertFalse(AmapMcpToolProvider.isExcluded("maps_weather_extra", List.of("maps_weather")),
                "不带 * 的 entry 必须全名精确匹配");
        assertFalse(AmapMcpToolProvider.isExcluded("MAPS_WEATHER", List.of("maps_weather")), "匹配大小写敏感");
        assertFalse(AmapMcpToolProvider.isExcluded("maps_weather", List.of()), "空白名单不排除任何工具");
        assertFalse(AmapMcpToolProvider.isExcluded("maps_weather", List.of(" ", "")), "空 entry 应被忽略");
    }

    // ---------- 辅助 ----------

    /** 实测托管端点的 15 个工具（3 个 schema 元工具 + 12 个业务工具）。 */
    private static List<McpSchema.Tool> fifteenTools() {
        return List.of(
                tool("maps_schema_navi"),
                tool("maps_schema_take_taxi"),
                tool("maps_schema_personal_map"),
                tool("maps_text_search"),
                tool("maps_around_search"),
                tool("maps_search_detail"),
                tool("maps_geo"),
                tool("maps_regeocode"),
                tool("maps_ip_location"),
                tool("maps_weather"),
                tool("maps_distance"),
                tool("maps_direction_driving"),
                tool("maps_direction_walking"),
                tool("maps_direction_transit_integrated"),
                tool("maps_direction_bicycling"));
    }

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
