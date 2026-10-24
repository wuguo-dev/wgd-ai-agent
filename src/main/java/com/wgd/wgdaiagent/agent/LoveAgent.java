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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

    /** 判定模型层异常时向下钻 cause 链的最大深度。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** 框架把模型异常伪装成答复时的前缀（{@code AgentLlmNode} 硬编码）。 */
    static final String FRAMEWORK_ERROR_PREFIX = "Exception: ";

    /** 日志脱敏：{@code key=...} 片段。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("key=[^&\\s]+");

    /** 日志里允许出现的异常文本长度。 */
    private static final int MAX_LOG_TEXT_LENGTH = 200;

    private static final String AGENT_NAME = "love_agent";

    private final ReactAgent agent;

    private final McpToolset mcpToolset;

    private final List<String> toolNames;

    private final int localToolCount;

    /** 记住模型层失败，用于把被框架吞成文本的异常还原成原始类型。 */
    private final ChatModelFailureRecorder chatModelFailureRecorder;

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

        // 包一层：AgentLlmNode 会把模型异常吞成 "Exception: ..." 文本，这里把原始异常记下来，
        // 供 chat() 还原成 TransientAiException/NonTransientAiException（§7.3 的 503/502 判据）
        this.chatModelFailureRecorder = new ChatModelFailureRecorder(dashscopeChatModel);

        BaseCheckpointSaver saver = AgentCheckpointSaver.create(properties.getMemory());
        this.agent = ReactAgent.builder()
                .name(AGENT_NAME)
                .model(chatModelFailureRecorder)
                .systemPrompt(promptLoader.text())
                .tools(allTools)
                .hooks(new HistoryWindowHook(properties.getHistoryWindow()))
                .compileConfig(compileConfig(saver))
                .toolExecutionExceptionProcessor(new LoveToolExceptionProcessor())
                // 默认关闭：打开后 AgentLlmNode 会在 INFO 打印 system prompt / 工具入参 / 答复全文（§7.4）
                .enableLogging(properties.isLogReasoning())
                .build();

        log.info("agent 就绪：本地工具={}，MCP 工具={}", localToolCount, mcpTools.size());
        log.info("agent 工具清单（{} 个）: {}", toolNames.size(), toolNames);
        if (!mcpToolset.available()) {
            // 降级 WARN 由 AmapMcpToolProvider 打一条（AC5 要求恰好 1 条），这里只留调试信息
            log.debug("MCP 工具不可用（{}），仅本地工具可用", mcpToolset.status());
        }
    }

    /**
     * 同步问答。
     *
     * <p>用 {@code agent.call(...)} 取最终答复（框架的 call 路径固定走非流式，无法像
     * {@link #stream(String, String)} 那样让异常在订阅期冒泡）。因此模型层失败通过
     * {@link ChatModelFailureRecorder} 在模型返回处捕获并还原成原始类型，保证
     * {@code TransientAiException}/{@code NonTransientAiException} 能到接口层（§7.3 的 503/502）；
     * 万一失败没被记录到，{@code "Exception: ..."} 哨兵文本也会被拦下，绝不作为答复返回。</p>
     *
     * @param chatId  会话 ID（白名单 {@code ^[A-Za-z0-9_-]{1,64}$}），直接作为 checkpoint 的 threadId
     * @param message 本轮用户消息；历史由 saver 恢复，调用方只需传本轮内容
     * @return 模型最终答复文本
     */
    public String chat(String chatId, String message) {
        String threadId = requireValidChatId(chatId);
        long startedAt = System.nanoTime();
        chatModelFailureRecorder.clear();
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            AssistantMessage assistantMessage = agent.call(new UserMessage(message), config);
            RuntimeException modelFailure = chatModelFailureRecorder.getAndClear();
            if (modelFailure != null) {
                // 框架把异常吞成了 "Exception: ..." 文本，这里还原成原始类型上抛
                throw translate(modelFailure, threadId);
            }
            String answer = extractAnswer(assistantMessage, threadId);
            log.info("[chatId={}] chat 完成 elapsedMs={} answerChars={}",
                    threadId, (System.nanoTime() - startedAt) / 1_000_000L, answer.length());
            return answer;
        } catch (GraphRunnerException e) {
            throw translate(e, threadId);
        } catch (RuntimeException e) {
            throw translate(e, threadId);
        } finally {
            chatModelFailureRecorder.clear();
        }
    }

    /**
     * 真流式问答：只吐增量文本，{@code NodeOutput → 文本} 的映射在这里做完（S4 不接触 graph 类型）。
     *
     * <p>语义：0..N 个增量文本；正常结束时 {@code Flux} 完成且不发结束标记；出错时以
     * {@link LoveAgentException} 终止（{@code TransientAiException} 原样穿透）。若模型一个
     * token 都没流出来，用最终消息全文兜底补发一次（最多一次）。</p>
     *
     * <p>与 {@link #chat} 一致，流式也要防「框架把异常伪装成答复」：开头只缓冲到
     * {@code "Exception: "} 的长度，命中就以错误终止且一个 delta 都不下发
     * （否则 SSE 会变成「200 + 英文异常逐字下发 + done」，违反 §7.3）。</p>
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
            chatModelFailureRecorder.clear();
            AtomicBoolean chunkEmitted = new AtomicBoolean(false);
            StreamSentinelGuard sentinelGuard = new StreamSentinelGuard(threadId);
            long startedAt = System.nanoTime();
            return outputs
                    .<String>handle((output, sink) -> AgentOutputMapper.toDelta(output, chunkEmitted.get())
                            .ifPresent(delta -> {
                                chunkEmitted.set(true);
                                sink.next(delta);
                            }))
                    .transform(sentinelGuard::apply)
                    .doOnComplete(() -> log.info("[chatId={}] stream 完成 elapsedMs={} chunkEmitted={}",
                            threadId, (System.nanoTime() - startedAt) / 1_000_000L, chunkEmitted.get()))
                    .onErrorResume(error -> {
                        // 优先用记录器里的原始异常：框架会把模型异常包成图异常/文本
                        RuntimeException original = chatModelFailureRecorder.getAndClear();
                        return Flux.error(translate(original != null ? original : error, threadId));
                    })
                    .doFinally(signal -> chatModelFailureRecorder.clear());
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

    private static String extractAnswer(AssistantMessage assistantMessage, String threadId) {
        if (assistantMessage == null) {
            throw new LoveAgentException(AgentErrorCode.INTERNAL_ERROR, "模型未返回任何结果");
        }
        // 最后一条仍是「要调工具」=> 递归上限截断了循环，没有最终答复
        if (assistantMessage.hasToolCalls()) {
            throw new LoveAgentException(AgentErrorCode.AGENT_LOOP_LIMIT,
                    "工具循环超过上限（" + RECURSION_LIMIT + " 步）仍未得出结论");
        }
        String text = assistantMessage.getText();
        if (text == null) {
            return "";
        }
        if (looksLikeFrameworkError(text)) {
            // 最后一道防线：框架把异常吞成了文本，且没走到记录器（异常并非由模型抛出）
            log.warn("[chatId={}] 拦截框架吞下的模型异常文本（{}）", threadId, sanitize(text));
            throw new LoveAgentException(classifyFrameworkError(text), "模型调用失败，请稍后重试");
        }
        return text;
    }

    /** 框架在 {@code AgentLlmNode} 里用 {@code "Exception: " + message} 伪装成答复。 */
    private static boolean looksLikeFrameworkError(String text) {
        return text.regionMatches(true, 0, FRAMEWORK_ERROR_PREFIX, 0, FRAMEWORK_ERROR_PREFIX.length());
    }

    /** 只剩分类用途：真正的原因已经在 {@link ChatModelFailureRecorder} 里还原成原始异常。 */
    private static AgentErrorCode classifyFrameworkError(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "429", "throttling", "rate limit", "too many requests", "timed out", "timeout")) {
            return AgentErrorCode.AGENT_UNAVAILABLE;
        }
        return AgentErrorCode.AGENT_ERROR;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 日志脱敏：抹掉 key 片段并把正文截断（§7.4）。 */
    static String sanitize(String text) {
        String masked = KEY_PATTERN.matcher(text).replaceAll("key=***");
        return masked.length() <= MAX_LOG_TEXT_LENGTH ? masked : masked.substring(0, MAX_LOG_TEXT_LENGTH) + "...";
    }

    private static String requireValidChatId(String chatId) {
        if (!StringUtils.hasText(chatId) || !CHAT_ID_PATTERN.matcher(chatId).matches()) {
            throw new LoveAgentException(AgentErrorCode.INVALID_REQUEST,
                    "chatId 只允许 1-64 位字母、数字、下划线或短横线");
        }
        return chatId;
    }

    /** 异常翻译：模型层异常（可能在 cause 链里）原样穿透，其余归一成内核异常。 */
    private static RuntimeException translate(Throwable error, String threadId) {
        RuntimeException modelException = findModelException(error);
        if (modelException != null) {
            return modelException;
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

    /**
     * 沿 cause 链下钻找模型层异常：框架会把它包进 {@code GraphRunnerException} 或
     * {@code CompletionException}，而接口层需要原始类型来做 503/502 判定。
     */
    private static RuntimeException findModelException(Throwable error) {
        Throwable current = error;
        // 加深度上限，防止异常链自身成环时不返回
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TransientAiException || current instanceof NonTransientAiException) {
                return (RuntimeException) current;
            }
            if (current.getCause() == current) {
                return null;
            }
            current = current.getCause();
        }
        return null;
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

    /**
     * {@link ChatModel} 装饰器：只做一件事——把模型抛出的异常记在<b>当前线程</b>上。
     *
     * <p>必须这么做，因为 {@code AgentLlmNode} 的两个分支都会 {@code catch (Exception)} 后把
     * 异常变成 {@code "Exception: " + message} 的答复文本，导致 429/401 退化成 HTTP 200 的假答复
     * （规格 §7.3 要求 429→503、401/403→502）。记录器让 {@code chat()} 能拿回原始异常类型，
     * 同时不做任何文本猜测。用 {@link ThreadLocal} 是因为 {@code chat()} 是同步调用、图在调用线程上跑，
     * 这样并发会话之间不会串。
     */
    static final class ChatModelFailureRecorder implements ChatModel {

        private final ChatModel delegate;

        private final ThreadLocal<RuntimeException> lastFailure = new ThreadLocal<>();

        ChatModelFailureRecorder(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                return delegate.call(prompt);
            } catch (RuntimeException e) {
                lastFailure.set(e);
                throw e;
            }
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            try {
                // 流式异常发生在订阅/推流期，用 doOnError 记录；同步抛出的在 catch 里记录
                return delegate.stream(prompt).doOnError(this::record);
            } catch (RuntimeException e) {
                record(e);
                throw e;
            }
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        private void record(Throwable error) {
            if (error instanceof RuntimeException runtimeException) {
                lastFailure.set(runtimeException);
            }
        }

        RuntimeException getAndClear() {
            try {
                return lastFailure.get();
            } finally {
                lastFailure.remove();
            }
        }

        void clear() {
            lastFailure.remove();
        }
    }

    /**
     * 流式哨兵：把「框架把异常伪装成答复文本」这种情况挡在 SSE 之外（规格 §7.3）。
     *
     * <p>只缓冲到 {@link #FRAMEWORK_ERROR_PREFIX} 的长度（11 字符），不会把整段答复攒起来：</p>
     * <ul>
     *   <li>缓冲内容一旦和前缀不匹配 → 立即 flush 并进入直通模式（后续 delta 原样下发）；</li>
     *   <li>缓冲内容完整命中前缀 → 转入「只收集不下发」模式，流结束时以
     *       {@link LoveAgentException} 终止，<b>一个 delta 都不许发出</b>；</li>
     *   <li>流结束时仍在探测（例如答复只有「收到」两个字）→ 把缓冲内容作为最后一段发出。</li>
     * </ul>
     */
    private static final class StreamSentinelGuard {

        private static final int PROBING = 0;

        private static final int PASS_THROUGH = 1;

        private static final int SENTINEL = 2;

        private final String threadId;

        private final StringBuilder head = new StringBuilder(FRAMEWORK_ERROR_PREFIX.length());

        /** 只用于命中后的分类与脱敏日志，上限 {@link #MAX_LOG_TEXT_LENGTH} 字符。 */
        private final StringBuilder evidence = new StringBuilder();

        private int state = PROBING;

        private StreamSentinelGuard(String threadId) {
            this.threadId = threadId;
        }

        Flux<String> apply(Flux<String> source) {
            return source.<String>handle(this::onDelta)
                    .concatWith(Flux.defer(this::completionSignal));
        }

        private void onDelta(String delta, SynchronousSink<String> sink) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            switch (state) {
                case PASS_THROUGH -> sink.next(delta);
                case SENTINEL -> appendEvidence(delta);
                default -> probe(delta, sink);
            }
        }

        private void probe(String delta, SynchronousSink<String> sink) {
            int take = Math.min(delta.length(), FRAMEWORK_ERROR_PREFIX.length() - head.length());
            head.append(delta, 0, take);
            if (matchedPrefixLength(head) < head.length()) {
                // 与哨兵前缀不匹配：立刻 flush 并恢复直通，绝不攒整段答复
                state = PASS_THROUGH;
                String flush = head + delta.substring(take);
                head.setLength(0);
                if (!flush.isEmpty()) {
                    sink.next(flush);
                }
                return;
            }
            if (head.length() >= FRAMEWORK_ERROR_PREFIX.length()) {
                // 完整命中："Exception: " → 不再下发任何文本，只留分类依据
                state = SENTINEL;
                appendEvidence(head.toString());
                appendEvidence(delta.substring(take));
            }
        }

        /** 流自然结束时：要么补发仍在缓冲的尾巴，要么把哨兵变成错误信号。 */
        private Flux<String> completionSignal() {
            if (state == SENTINEL) {
                String text = evidence.toString();
                log.warn("[chatId={}] 拦截流式异常文本（{}）", threadId, sanitize(text));
                return Flux.error(new LoveAgentException(classifyFrameworkError(text), "模型调用失败，请稍后重试"));
            }
            if (head.length() > 0) {
                String tail = head.toString();
                head.setLength(0);
                return Flux.just(tail);
            }
            return Flux.empty();
        }

        private void appendEvidence(String text) {
            if (text == null || text.isEmpty() || evidence.length() >= MAX_LOG_TEXT_LENGTH) {
                return;
            }
            int room = MAX_LOG_TEXT_LENGTH - evidence.length();
            evidence.append(text, 0, Math.min(text.length(), room));
        }

        /** 返回 {@code text} 与哨兵前缀逐字符匹配（忽略大小写）的长度。 */
        private static int matchedPrefixLength(CharSequence text) {
            int max = Math.min(text.length(), FRAMEWORK_ERROR_PREFIX.length());
            for (int i = 0; i < max; i++) {
                if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(FRAMEWORK_ERROR_PREFIX.charAt(i))) {
                    return i;
                }
            }
            return max;
        }
    }
}
