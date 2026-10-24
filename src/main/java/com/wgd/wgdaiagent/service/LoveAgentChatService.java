package com.wgd.wgdaiagent.service;

import com.wgd.wgdaiagent.agent.LoveAgent;
import com.wgd.wgdaiagent.exception.AgentErrorCode;
import com.wgd.wgdaiagent.exception.LoveAgentException;
import com.wgd.wgdaiagent.model.LoveAgentChatRequest;
import com.wgd.wgdaiagent.model.LoveAgentChatResponse;
import com.wgd.wgdaiagent.model.LoveAgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * 恋爱大师接口层业务服务（规格 §4.2 / §6.2 / §6.3）。
 *
 * <p>只做四件事，且刻意<b>不</b>碰 agent 内核的图执行类型（本类只依赖 {@link LoveAgent} 的
 * 四个公开方法，图输出到文本的映射已在 S3 完成）：</p>
 * <ol>
 *   <li>{@code chatId} 规范化：空白 → 生成 UUID（去横线）；给出则按白名单二次校验；</li>
 *   <li>调 {@link LoveAgent#chat} / {@link LoveAgent#stream}；</li>
 *   <li>组装同步响应与 SSE 事件（{@code message}/{@code done}/{@code error}）；</li>
 *   <li>异常翻译成 {@link LoveAgentException}（携带 {@link AgentErrorCode}），供接口层映射 HTTP。</li>
 * </ol>
 *
 * <p>并发：同一 {@code chatId} 必须串行（规格 §4.4，避免同一 threadId 的 checkpoint 竞争）。
 * 这里没有照抄 {@code ConcurrentHashMap<String, ReentrantLock>}——那会让锁条目随会话数无界增长，
 * 而是用 <b>固定 64 段</b>的互斥量数组（{@code hash & 63} 的等价写法 {@link Math#floorMod}），
 * 内存恒定。段内用 {@link Semaphore}（1 个许可）而不是 {@code ReentrantLock}：流式任务可能在
 * 与获取锁不同的线程上终止（reactor 的 doFinally），而 {@code ReentrantLock} 要求同线程释放，
 * {@link Semaphore} 没有 owner 限制。代价是极小概率的哈希碰撞会让两个不同 chatId 短暂互相等待。</p>
 */
@Slf4j
@Service
public class LoveAgentChatService {

    /** 分段数：固定大小，避免 per-session 锁表泄漏（§4.4 的偏差，报告里说明）。 */
    static final int SEGMENTS = 64;

    /** chatId 白名单；与 {@code LoveAgent}、旧记忆类的校验保持一致（R14）。 */
    private static final Pattern CHAT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final String INVALID_CHAT_ID_MESSAGE = "chatId 只允许 1-64 位字母、数字、下划线或短横线";

    private static final String UNAVAILABLE_MESSAGE = "模型服务暂时不可用，请稍后重试";

    private static final String REJECTED_MESSAGE = "模型服务拒绝了请求，请稍后重试";

    private static final String INTERNAL_MESSAGE = "服务内部错误，请稍后重试";

    private final LoveAgent loveAgent;

    /** 固定 64 段互斥量；索引 = {@code Math.floorMod(chatId.hashCode(), 64)}。 */
    private final Semaphore[] segments;

    public LoveAgentChatService(LoveAgent loveAgent) {
        this.loveAgent = loveAgent;
        this.segments = new Semaphore[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            this.segments[i] = new Semaphore(1);
        }
    }

    /**
     * 同步问答：阻塞直到 agent 给出最终答复。
     *
     * @param request 已通过 Bean Validation 的请求
     * @return 含规范化 {@code chatId}、答复与耗时的响应
     * @throws LoveAgentException 参数、模型或内核失败；{@code getCode()} 决定 HTTP 状态（§7.3）
     */
    public LoveAgentChatResponse chat(LoveAgentChatRequest request) {
        String chatId = normalizeChatId(request.chatId());
        Semaphore segment = segmentFor(chatId);
        segment.acquireUninterruptibly();
        long startedAt = System.nanoTime();
        try {
            String answer = loveAgent.chat(chatId, request.message());
            long elapsedMs = elapsedMillis(startedAt);
            log.info("[chatId={}] 同步问答完成 elapsedMs={} answerChars={}",
                    chatId, elapsedMs, lengthOf(answer));
            return new LoveAgentChatResponse(chatId, answer, elapsedMs);
        } catch (RuntimeException error) {
            throw translate(error, chatId);
        } finally {
            segment.release();
        }
    }

    /**
     * 流式问答：返回的 {@link Flux} 只吐 SSE 事件（0..N 个 {@code message} + 至多一个终止帧）。
     *
     * <p>chatId 的规范化在方法调用时<b>同步</b>完成（非法 chatId 同步抛，接口层可在建流前返回 400）；
     * 真正的订阅、加锁、调 agent 都在订阅期执行。终止语义：正常 → 一个 {@code done} 后完成；
     * 出错 → 一个 {@code error} 后完成（二者互斥，绝不都出现）。</p>
     *
     * @param request 已通过 Bean Validation 的请求
     * @return SSE 事件流（不含有状态，每个订阅独立执行）
     */
    public Flux<LoveAgentStreamEvent> stream(LoveAgentChatRequest request) {
        // 入口同步校验：非法 chatId 直接抛出（不是 error flux），满足「建流前返回 400」
        String chatId = normalizeChatId(request.chatId());
        return Flux.defer(() -> streamSession(chatId, request.message()));
    }

    /**
     * 订阅期执行一次完整会话：先拿同 chatId 的段锁，再调 agent，最后按结果发终止帧。
     *
     * <p>锁持有范围覆盖整个流（含下游消费），{@code doFinally} 保证正常完成、出错、客户端取消
     * 三种路径都会释放。</p>
     */
    private Flux<LoveAgentStreamEvent> streamSession(String chatId, String message) {
        Semaphore segment = segmentFor(chatId);
        segment.acquireUninterruptibly();
        long startedAt = System.nanoTime();
        Flux<LoveAgentStreamEvent> events;
        try {
            events = loveAgent.stream(chatId, message)
                    .filter(StringUtils::hasText)
                    .map(delta -> LoveAgentStreamEvent.delta(chatId, delta))
                    .concatWith(Flux.defer(() -> {
                        long elapsedMs = elapsedMillis(startedAt);
                        log.info("[chatId={}] 流式问答完成 elapsedMs={}", chatId, elapsedMs);
                        return Flux.just(LoveAgentStreamEvent.done(chatId, elapsedMs));
                    }))
                    .onErrorResume(error -> Flux.just(
                            errorEvent(translate(asRuntime(error), chatId), chatId, error)));
        } catch (RuntimeException error) {
            // agent.stream 的入口同步抛（例如 chatId 校验失败）：SSE 已建立，只能以 error 帧收尾
            events = Flux.just(errorEvent(translate(error, chatId), chatId, error));
        }
        return events.doFinally(signal -> segment.release());
    }

    /**
     * chatId 规范化：null/空/纯空白 → UUID v4 去横线（32 位十六进制）；否则 trim 后校验白名单。
     */
    static String normalizeChatId(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String trimmed = chatId.trim();
        if (!CHAT_ID_PATTERN.matcher(trimmed).matches()) {
            throw new LoveAgentException(AgentErrorCode.INVALID_REQUEST, INVALID_CHAT_ID_MESSAGE);
        }
        return trimmed;
    }

    /** 异常翻译：保留内核业务异常，模型层异常归一成 503/502，其余兜底 500（§7.3）。 */
    private static LoveAgentException translate(RuntimeException error, String chatId) {
        if (error instanceof LoveAgentException loveAgentException) {
            log.warn("[chatId={}] agent 业务异常 code={} message={}",
                    chatId, loveAgentException.getCode(), loveAgentException.getMessage());
            return loveAgentException;
        }
        if (error instanceof TransientAiException) {
            log.error("[chatId={}] 模型暂时不可用（{}）", chatId, error.getClass().getSimpleName());
            return new LoveAgentException(AgentErrorCode.AGENT_UNAVAILABLE, UNAVAILABLE_MESSAGE, error);
        }
        if (error instanceof NonTransientAiException) {
            log.error("[chatId={}] 模型永久性失败（{}）", chatId, error.getClass().getSimpleName());
            return new LoveAgentException(AgentErrorCode.AGENT_ERROR, REJECTED_MESSAGE, error);
        }
        log.error("[chatId={}] agent 未预期异常（{}）", chatId, error.getClass().getSimpleName());
        return new LoveAgentException(AgentErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE, error);
    }

    /** 组装 error 帧；500 类错误在这里补一条服务端堆栈（响应体里永远没有）。 */
    private static LoveAgentStreamEvent errorEvent(LoveAgentException exception, String chatId, Throwable cause) {
        AgentErrorCode code = exception.getCode();
        if (code == AgentErrorCode.INTERNAL_ERROR || code == AgentErrorCode.AGENT_LOOP_LIMIT) {
            log.error("[chatId={}] 流式问答失败 code={}", chatId, code, cause);
        }
        String message = StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : code.name();
        return LoveAgentStreamEvent.error(chatId, code.name(), message);
    }

    /** 固定分段：索引恒定落在 [0, 63]，会话再多也不会新增锁对象。 */
    private Semaphore segmentFor(String chatId) {
        return segments[Math.floorMod(chatId.hashCode(), SEGMENTS)];
    }

    /** reactor 的错误信号是 {@link Throwable}，内核只抛 unchecked；checked 情况包一层再翻译。 */
    private static RuntimeException asRuntime(Throwable error) {
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(error.getMessage(), error);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }
}
