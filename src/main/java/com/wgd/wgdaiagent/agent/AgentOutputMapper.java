package com.wgd.wgdaiagent.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * {@code NodeOutput} → 增量文本（规格 D15 / §6.3）。纯函数，不持有状态。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只认 {@link OutputType#AGENT_MODEL_STREAMING} 的非空 {@code chunk()}，这是 token 级切片；</li>
 *   <li>{@link OutputType#AGENT_MODEL_FINISHED} <b>仅在「一个 chunk 都没发过」时</b>用
 *       {@code message().getText()} 兜底补发全文，避免与流式切片重复；</li>
 *   <li>{@code AGENT_TOOL_*}、{@code AGENT_HOOK_*}、{@code GRAPH_NODE_*} 与普通
 *       {@link NodeOutput} 一律忽略。</li>
 * </ul>
 *
 * <p>「是否已经发过 chunk」由调用方以参数传入，所以本类保持无状态（可以安全地并发复用）。</p>
 */
public final class AgentOutputMapper {

    private AgentOutputMapper() {
    }

    /**
     * @param output             图输出
     * @param chunkAlreadyEmitted 本次流式回合是否已经发出过 token 切片
     * @return 需要发给客户端的增量文本；不需要发时返回 {@link Optional#empty()}
     */
    public static Optional<String> toDelta(NodeOutput output, boolean chunkAlreadyEmitted) {
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return Optional.empty();
        }
        OutputType outputType = streamingOutput.getOutputType();
        if (outputType == OutputType.AGENT_MODEL_STREAMING) {
            String chunk = streamingOutput.chunk();
            return StringUtils.hasLength(chunk) ? Optional.of(chunk) : Optional.empty();
        }
        if (outputType == OutputType.AGENT_MODEL_FINISHED && !chunkAlreadyEmitted) {
            return fallbackText(streamingOutput);
        }
        return Optional.empty();
    }

    /** 兜底：优先取最终消息正文，取不到再退回 chunk。 */
    private static Optional<String> fallbackText(StreamingOutput<?> streamingOutput) {
        Message message = streamingOutput.message();
        String text = message == null ? null : message.getText();
        if (StringUtils.hasText(text)) {
            return Optional.of(text);
        }
        String chunk = streamingOutput.chunk();
        return StringUtils.hasText(chunk) ? Optional.of(chunk) : Optional.empty();
    }
}
