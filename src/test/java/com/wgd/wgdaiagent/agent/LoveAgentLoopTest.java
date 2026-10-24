package com.wgd.wgdaiagent.agent;

import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.tool.AmapMcpToolProvider;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import com.wgd.wgdaiagent.tool.McpToolset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12–T15（规格 §9.3）：工具循环、工具异常降级、递归上限、system prompt 逐字透传与工具数。
 *
 * <p>全部离线：假 {@link ChatModel} 不联网，假 {@link VectorStore} 只记账，MCP 用 stub 工具集。
 * 注意（§9.5）：{@code AgentLlmNode} 默认走流式，{@code LoveAgent.chat} 显式置
 * {@code _stream_=false} 走非流式，所以假模型只需实现 {@code call(Prompt)}。</p>
 */
@Tag("offline")
class LoveAgentLoopTest {

    @Test
    void toolCallRoundTripUsesKnowledgeBaseOnce() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        ScriptedChatModel model = new ScriptedChatModel(prompt -> hasToolResponse(prompt)
                ? text("先固定沟通节奏，再谈结束异地的时间点。")
                : toolCall("call-1", "loveKbSearch", "{\"query\":\"异地恋怎么维持\"}"));
        LoveAgent agent = newAgent(model, emptyMcpToolset(), new LoveLocalTools(provider(vectorStore)));

        String answer = agent.chat("chat-1", "异地恋怎么维持");

        assertEquals("先固定沟通节奏，再谈结束异地的时间点。", answer);
        assertEquals(1, vectorStore.calls, "知识库工具必须真实执行一次");
        assertEquals(2, model.prompts.size(), "一轮工具调用 = 两次模型调用");
        ToolResponseMessage toolResponse = lastToolResponse(model.prompts.get(1));
        assertEquals("loveKbSearch", toolResponse.getResponses().get(0).name());
        assertTrue(toolResponse.getResponses().get(0).responseData().contains("filename="),
                "回喂给模型的应是知识库格式化结果：" + toolResponse.getResponses().get(0).responseData());
    }

    @Test
    void toolFailureIsTurnedIntoReadableTextAndLoopContinues() {
        ScriptedChatModel model = new ScriptedChatModel(prompt -> hasToolResponse(prompt)
                ? text("天气查不到，先按室内约会安排。")
                : toolCall("call-9", "maps_weather", "{\"city\":\"北京\"}"));
        McpToolset failingMcp = new McpToolset(
                List.of(toolCallback("maps_weather", () -> {
                    throw new IllegalStateException("secret jdbc url");
                })), true, "ok");
        LoveAgent agent = newAgent(model, failingMcp, new LoveLocalTools(provider(null)));

        String answer = agent.chat("chat-1", "北京周末天气怎么样");

        assertEquals("天气查不到，先按室内约会安排。", answer, "工具失败不能打断循环");
        String toolResponse = lastToolResponse(model.prompts.get(1)).getResponses().get(0).responseData();
        assertTrue(toolResponse.contains("maps_weather"), "错误文本必须带工具名：" + toolResponse);
        assertTrue(toolResponse.contains("调用失败"), toolResponse);
        assertTrue(toolResponse.contains("请基于已有信息继续回答"), toolResponse);
        assertTrue(toolResponse.contains("服务暂时不可用"), "按异常类别给原因：" + toolResponse);
        assertTrue(!toolResponse.contains("secret"), "异常 message 不得回喂模型：" + toolResponse);
    }

    @Test
    void exhaustedToolLoopRaisesLoopLimit() {
        ScriptedChatModel model = new ScriptedChatModel(
                prompt -> toolCall("call-loop", "loveKbSearch", "{\"query\":\"再来一次\"}"));
        LoveAgent agent = newAgent(model, emptyMcpToolset(), new LoveLocalTools(provider(null)));

        LoveAgentException error = assertThrows(LoveAgentException.class, () -> agent.chat("chat-1", "一直调工具"));

        assertEquals(AgentErrorCode.AGENT_LOOP_LIMIT, error.getCode(), "递归上限耗尽 → AGENT_LOOP_LIMIT");
    }

    @Test
    void systemPromptIsPassedVerbatimAndAllToolsAreExposed(@TempDir Path tempDir) throws Exception {
        String promptText = "你是恋爱顾问。请为{用户名}生成报告，示例 JSON：{\"advice\":\"先共情\"}";
        Path promptFile = Files.writeString(tempDir.resolve("system.txt"), promptText, StandardCharsets.UTF_8);
        ScriptedChatModel model = new ScriptedChatModel(prompt -> text("收到"));
        McpToolset oneMcpTool = new McpToolset(List.of(toolCallback("maps_weather", () -> "晴")), true, "ok");
        AgentProperties properties = inMemoryProperties();

        LoveAgent agent = new LoveAgent(model, properties, new PromptLoader(promptFile.toUri().toString()),
                mcpProvider(properties, oneMcpTool), new LoveLocalTools(provider(null)));

        agent.chat("chat-1", "你好");

        Prompt firstPrompt = model.prompts.get(0);
        Message systemMessage = firstPrompt.getInstructions().get(0);
        assertInstanceOf(SystemMessage.class, systemMessage, "systemPrompt 必须变成第一条 SystemMessage");
        assertEquals(promptText, systemMessage.getText(), "含 {} 的 prompt 必须逐字透传，不能被当模板渲染");
        assertEquals(3, toolCallbacksOf(firstPrompt).size(), "工具定义数 = 本地 2 + stub MCP 1");
        assertEquals(List.of("currentTime", "loveKbSearch", "maps_weather"), agent.toolNames(),
                "顺序固定：本地 2 个在前，MCP 按 listTools 顺序在后");
    }

    @Test
    void streamEmitsIncrementalTextAndCompletes(@TempDir Path tempDir) {
        ScriptedChatModel model = new ScriptedChatModel(nextPrompt -> text("流式答复"));
        LoveAgent agent = newAgent(model, emptyMcpToolset(), new LoveLocalTools(provider(null)));

        List<String> deltas = agent.stream("chat-stream", "你好").collectList().block(java.time.Duration.ofSeconds(30));

        assertTrue(deltas != null && !deltas.isEmpty(), "流式必须至少吐出一段增量文本，实际：" + deltas);
        assertEquals("流式答复", String.join("", deltas), "增量文本拼起来等于完整答复");
    }

    // ---------- 共享测试替身（Session/Concurrency 用例复用） ----------

    /** 假 ChatModel：按回调决定回复，并记录每次收到的 Prompt。 */
    static final class ScriptedChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> responder;

        final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

        ScriptedChatModel(Function<Prompt, ChatResponse> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return responder.apply(prompt);
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            // 流式路径：把同一份完整答复当成单个 chunk 推出（§9.5 的退路做法）
            return reactor.core.publisher.Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    static ChatResponse toolCall(String id, String toolName, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    static boolean hasToolResponse(Prompt prompt) {
        return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
    }

    static ToolResponseMessage lastToolResponse(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("Prompt 中没有 ToolResponseMessage"));
    }

    static List<ToolCallback> toolCallbacksOf(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolCallingChatOptions) {
            return toolCallingChatOptions.getToolCallbacks();
        }
        return List.of();
    }

    static AgentProperties inMemoryProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setType(AgentProperties.Memory.Type.MEMORY);
        return properties;
    }

    static McpToolset emptyMcpToolset() {
        return new McpToolset(List.of(), false, "未配置 MCP key（环境变量 AMAP_MCP_KEY）");
    }

    static AmapMcpToolProvider mcpProvider(AgentProperties properties, McpToolset toolset) {
        return new AmapMcpToolProvider(properties) {
            @Override
            public McpToolset toolset() {
                return toolset;
            }
        };
    }

    static LoveAgent newAgent(ScriptedChatModel model, McpToolset toolset, LoveLocalTools localTools) {
        AgentProperties properties = inMemoryProperties();
        return new LoveAgent(model, properties, new PromptLoader(), mcpProvider(properties, toolset), localTools);
    }

    static ToolCallback toolCallback(String name, java.util.function.Supplier<String> result) {
        return new ToolCallback() {

            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(name + " 的描述")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                try {
                    return result.get();
                } catch (RuntimeException e) {
                    // 与 MethodToolCallback / SyncMcpToolCallback 一致：工具异常包成 ToolExecutionException，
                    // AgentToolNode 才会交给 ToolExecutionExceptionProcessor 翻译
                    throw new ToolExecutionException(definition, e);
                }
            }
        };
    }

    static ObjectProvider<VectorStore> provider(VectorStore vectorStore) {
        return new ObjectProvider<>() {
            @Override
            public VectorStore getIfAvailable() {
                return vectorStore;
            }
        };
    }

    /** 假 VectorStore：记录检索次数并返回一条带 filename 的文档。 */
    static final class RecordingVectorStore implements VectorStore {

        int calls;

        @Override
        public void add(List<Document> documents) {
            throw new UnsupportedOperationException("离线用例不写向量库");
        }

        @Override
        public void delete(List<String> ids) {
            throw new UnsupportedOperationException("离线用例不删向量库");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("离线用例不删向量库");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            calls++;
            return List.of(Document.builder()
                    .text("异地恋要保持固定的沟通节奏。")
                    .metadata(Map.of("filename", "恋爱篇.md"))
                    .score(0.83d)
                    .build());
        }
    }
}
