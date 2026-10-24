package com.wgd.wgdaiagent.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T10（规格 §9.3 / D15）：{@code NodeOutput → 增量文本} 的映射规则。
 *
 * <p>{@code StreamingOutput} 非 final，按 §9.5 用 Mockito 打桩，不启动图。</p>
 */
@Tag("offline")
@SuppressWarnings("unchecked")
class AgentOutputMapperTest {

    @Test
    void streamsModelChunks() {
        StreamingOutput<?> output = streaming(OutputType.AGENT_MODEL_STREAMING, "北京", null);

        assertEquals(Optional.of("北京"), AgentOutputMapper.toDelta(output, false));
        assertEquals(Optional.of("北京"), AgentOutputMapper.toDelta(output, true), "已发过 chunk 也继续把新 chunk 发出去");
    }

    @Test
    void ignoresEmptyChunk() {
        assertEquals(Optional.empty(), AgentOutputMapper.toDelta(streaming(OutputType.AGENT_MODEL_STREAMING, "", null), false));
        assertEquals(Optional.empty(), AgentOutputMapper.toDelta(streaming(OutputType.AGENT_MODEL_STREAMING, null, null), false));
    }

    @Test
    void ignoresToolAndGraphOutputs() {
        for (OutputType type : new OutputType[] {
                OutputType.AGENT_TOOL_STREAMING,
                OutputType.AGENT_TOOL_FINISHED,
                OutputType.AGENT_HOOK_STREAMING,
                OutputType.AGENT_HOOK_FINISHED,
                OutputType.GRAPH_NODE_STREAMING,
                OutputType.GRAPH_NODE_FINISHED}) {
            assertEquals(Optional.empty(),
                    AgentOutputMapper.toDelta(streaming(type, "不该发出去", new AssistantMessage("不该发出去")), false),
                    "只认 AGENT_MODEL_STREAMING / 兜底用的 AGENT_MODEL_FINISHED，实际类型：" + type);
        }
    }

    @Test
    void finishedOutputIsUsedAsFallbackOnlyWhenNoChunkWasEmitted() {
        StreamingOutput<?> output = streaming(OutputType.AGENT_MODEL_FINISHED, null, new AssistantMessage("完整答复"));

        assertEquals(Optional.of("完整答复"), AgentOutputMapper.toDelta(output, false),
                "一个 chunk 都没发过时，用最终消息全文兜底");
        assertEquals(Optional.empty(), AgentOutputMapper.toDelta(output, true),
                "已经发过 chunk 就不能再补发全文，否则前端会看到重复文本");
    }

    @Test
    void finishedOutputFallsBackToChunkWhenMessageMissing() {
        StreamingOutput<?> output = streaming(OutputType.AGENT_MODEL_FINISHED, "兜底文本", null);

        assertEquals(Optional.of("兜底文本"), AgentOutputMapper.toDelta(output, false));
    }

    @Test
    void ignoresBlankFallback() {
        StreamingOutput<?> output = streaming(OutputType.AGENT_MODEL_FINISHED, "  ", new AssistantMessage(""));

        assertTrue(AgentOutputMapper.toDelta(output, false).isEmpty());
    }

    @Test
    void ignoresPlainNodeOutput() {
        assertEquals(Optional.empty(), AgentOutputMapper.toDelta(mock(NodeOutput.class), false),
                "非流式 NodeOutput（例如图的普通节点）不产生增量文本");
    }

    private static StreamingOutput<?> streaming(OutputType type, String chunk, AssistantMessage message) {
        StreamingOutput<?> output = mock(StreamingOutput.class);
        when(output.getOutputType()).thenReturn(type);
        when(output.chunk()).thenReturn(chunk);
        when(output.message()).thenReturn(message);
        return output;
    }
}
