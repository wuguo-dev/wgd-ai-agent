package com.wgd.wgdaiagent.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.tool.AmapMcpToolProvider;
import com.wgd.wgdaiagent.tool.LoggingToolCallback;
import com.wgd.wgdaiagent.tool.LoveLocalTools;
import com.wgd.wgdaiagent.tool.LoveToolExceptionProcessor;
import com.wgd.wgdaiagent.tool.McpToolset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 恋爱大师 Agent 内核：启动期装配唯一 {@link ReactAgent}，对外只暴露
 * {@link #chat(String, String)}、{@link #stream(String, String)}、{@link #isMcpAvailable()}、
 * {@link #toolNames()}（规格 §4.2 / §6.1）。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>系统提示词走 {@code systemPrompt(...)} 而非 {@code instruction(...)}（D14：后者会被当模板渲染）；</li>
 *   <li>工具一次性传给 {@code tools(List)}，不用 {@code toolCallbackProviders}/{@code toolNames}
 *       （否则 {@code build()} 时会再联网一次，与「连不上也要能启动」冲突，§4.5）；</li>
 *   <li>记忆走 saver + {@code threadId=chatId}，每轮只传本轮 {@code UserMessage}（D10）；</li>
 *   <li>工具执行串行、图递归上限 8（D12）。</li>
 * </ul>
 *
 * <p>错误语义：{@link LoveAgentException} 表达内核业务失败；{@code TransientAiException}/
 * {@code NonTransientAiException} 原样上抛，由接口层映射 503/502（§7.2）。</p>
 */
@Slf4j
@Component
public class LoveAgent {

    /** chatId 白名单：同时是 saver 文件名的一部分，禁止路径穿越（R14）。 */
    static final Pattern CHAT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 图递归上限：一轮内最多 8 步（D12）。 */
    static final int RECURSION_LIMIT = 8;

    /** AgentLlmNode 读的框架元数据：置 false 走非流式（同步接口用）。 */
    static final String STREAM_METADATA_KEY = "_stream_";

    private static final String AGENT_NAME = "love_agent";

    private final ReactAgent agent;

    private final McpToolset mcpToolset;

    private final List<String> toolNames;

    private final int localToolCount;

    public LoveAgent(ChatModel dashscopeChatModel,
                     AgentProperties properties,
                     PromptLoader promptLoader,
                     AmapMcpToolProvider amapMcpToolProvider,
                     LoveLocalTools loveLocalTools) {
        List<ToolCallback> localTools = Arrays.stream(ToolCallbacks.from(loveLocalTools))
                .map(LoggingToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        // 首次取工具集才建连；LoveAgent 是单例 Bean，所以这一步等价于「启动期建连」（§6.1）
        this.mcpToolset = amapMcpToolProvider.toolset();
        List<ToolCallback> mcpTools = mcpToolset.callbacks().stream()
                .map(LoggingToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();

        List<ToolCallback> allTools = new ArrayList<>(localTools.size() + mcpTools.size());
        allTools.addAll(localTools);
        allTools.addAll(mcpTools);
        this.localToolCount = localTools.size();
        this.toolNames = allTools.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        BaseCheckpointSaver saver = AgentCheckpointSaver.create(properties.getMemory());
        this.agent = ReactAgent.builder()
                .name(AGENT_NAME)
                .model(dashscopeChatModel)
                .systemPrompt(promptLoader.text())
                .tools(allTools)
                .hooks(new HistoryWindowHook(properties.getHistoryWindow()))
                .compileConfig(compileConfig(saver))
                .toolExecutionExceptionProcessor(new LoveToolExceptionProcessor())
                .enableLogging(true)
                .build();

        log.info("agent 就绪：本地工具={}，MCP 工具={}", localToolCount, mcpTools.size());
        log.info("agent 工具清单（{} 个）: {}", toolNames.size(), toolNames);
        if (!mcpToolset.available()) {
            log.warn("MCP 工具不可用（{}），仅本地工具可用", mcpToolset.status());
        }
    }

    /**
     * 同步问答。
     *
     * <p>注意：{@code AgentLlmNode} 在非流式分支里 {@code catch (Exception)} 后会把模型异常
     * 转成 {@code "Exception: ..."} 文本当作答复返回，所以同步路径上的 429/5xx 不一定以异常
     * 形式冒到这里（流式路径的异常发生在订阅期，能正常冒泡）。{@link #chat} 只能保证：
     * 凡是冒到本方法的模型层异常，一律原样上抛给接口层。</p>
     *
     * @param chatId  会话 ID（白名单 {@code ^[A-Za-z0-9_-]{1,64}$}），直接作为 checkpoint 的 threadId
     * @param message 本轮用户消息；历史由 saver 恢复，调用方只需传本轮内容
     * @return 模型最终答复文本
     */
    public String chat(String chatId, String message) {
        String threadId = requireValidChatId(chatId);
        long startedAt = System.nanoTime();
        try {
            // 同步接口不需要 token 级流：显式走非流式，结果确定、少一层聚合
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .addMetadata(STREAM_METADATA_KEY, false)
                    .build();
            AssistantMessage assistantMessage = agent.call(new UserMessage(message), config);
            String answer = extractAnswer(assistantMessage);
            log.info("[chatId={}] chat 完成 elapsedMs={} answerChars={}",
                    threadId, (System.nanoTime() - startedAt) / 1_000_000L, answer.length());
            return answer;
        } catch (GraphRunnerException e) {
            throw translate(e, threadId);
        } catch (RuntimeException e) {
            throw translate(e, threadId);
        }
    }

    /**
     * 真流式问答：只吐增量文本，{@code NodeOutput → 文本} 的映射在这里做完（S4 不接触 graph 类型）。
     *
     * <p>语义：0..N 个增量文本；正常结束时 {@code Flux} 完成且不发结束标记；出错时以
     * {@link LoveAgentException} 终止（{@code TransientAiException} 原样穿透）。若模型一个
     * token 都没流出来，用最终消息全文兜底补发一次（最多一次）。</p>
     *
     * @param chatId  会话 ID，规则同 {@link #chat(String, String)}
     * @param message 本轮用户消息
     */
    public Flux<String> stream(String chatId, String message) {
        String threadId = requireValidChatId(chatId);
        Flux<NodeOutput> outputs;
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            outputs = agent.stream(new UserMessage(message), config);
        } catch (GraphRunnerException e) {
            return Flux.error(translate(e, threadId));
        } catch (RuntimeException e) {
            return Flux.error(translate(e, threadId));
        }
        return Flux.defer(() -> {
            AtomicBoolean chunkEmitted = new AtomicBoolean(false);
            long startedAt = System.nanoTime();
            return outputs
                    .<String>handle((output, sink) -> AgentOutputMapper.toDelta(output, chunkEmitted.get())
                            .ifPresent(delta -> {
                                chunkEmitted.set(true);
                                sink.next(delta);
                            }))
                    .doOnComplete(() -> log.info("[chatId={}] stream 完成 elapsedMs={} chunkEmitted={}",
                            threadId, (System.nanoTime() - startedAt) / 1_000_000L, chunkEmitted.get()))
                    .onErrorMap(error -> translate(error, threadId));
        });
    }

    /** MCP 工具集是否可用（不可用时只剩两个本地工具）。 */
    public boolean isMcpAvailable() {
        return mcpToolset.available();
    }

    /** 模型实际看到的工具名（本地 2 个在前，MCP 按 {@code listTools()} 顺序在后）。 */
    public List<String> toolNames() {
        return toolNames;
    }

    /**
     * 一次装配 CompileConfig：递归上限 + saver。
     *
     * <p>注意：{@code Builder.buildConfig()} 在 {@code compileConfig != null} 时原样使用它，
     * 此时 {@code .saver(...)} 会被忽略，所以 saver 必须放进 CompileConfig 的 {@link SaverConfig}。</p>
     */
    private static CompileConfig compileConfig(BaseCheckpointSaver saver) {
        return CompileConfig.builder()
                .recursionLimit(RECURSION_LIMIT)
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();
    }

    private static String extractAnswer(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            throw new LoveAgentException(AgentErrorCode.INTERNAL_ERROR, "模型未返回任何结果");
        }
        // 最后一条仍是「要调工具」=> 递归上限截断了循环，没有最终答复
        if (assistantMessage.hasToolCalls()) {
            throw new LoveAgentException(AgentErrorCode.AGENT_LOOP_LIMIT,
                    "工具循环超过上限（" + RECURSION_LIMIT + " 步）仍未得出结论");
        }
        String text = assistantMessage.getText();
        return text == null ? "" : text;
    }

    private static String requireValidChatId(String chatId) {
        if (!StringUtils.hasText(chatId) || !CHAT_ID_PATTERN.matcher(chatId).matches()) {
            throw new LoveAgentException(AgentErrorCode.INVALID_REQUEST,
                    "chatId 只允许 1-64 位字母、数字、下划线或短横线");
        }
        return chatId;
    }

    /** 异常翻译：模型层异常原样穿透，其余归一成内核异常。 */
    private static RuntimeException translate(Throwable error, String threadId) {
        if (error instanceof TransientAiException || error instanceof NonTransientAiException) {
            return (RuntimeException) error;
        }
        if (error instanceof LoveAgentException loveAgentException) {
            return loveAgentException;
        }
        if (error instanceof GraphRunnerException graphRunnerException) {
            AgentErrorCode code = isLoopLimit(graphRunnerException)
                    ? AgentErrorCode.AGENT_LOOP_LIMIT
                    : AgentErrorCode.AGENT_ERROR;
            log.warn("[chatId={}] 图执行失败（{}）", threadId, graphRunnerException.getClass().getSimpleName());
            return new LoveAgentException(code, "agent 执行失败", graphRunnerException);
        }
        log.error("[chatId={}] agent 未预期异常", threadId, error);
        return new LoveAgentException(AgentErrorCode.INTERNAL_ERROR, "agent 内部错误", error);
    }

    private static boolean isLoopLimit(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("recursion") || lower.contains("iteration")) {
                    return true;
                }
            }
        }
        return false;
    }
}
