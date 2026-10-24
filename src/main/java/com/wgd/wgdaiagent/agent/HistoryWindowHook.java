package com.wgd.wgdaiagent.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 每次模型调用前把上下文裁到最近 {@code history-window} 条（规格 D17 / §4.4）。
 *
 * <p>saver 会持久化整段会话，不裁剪会无限膨胀。裁剪只做「保留」，不做摘要、不调模型。
 * 三条规则（结果只会往前扩，不会丢失配对）：</p>
 * <ol>
 *   <li>取最近 {@code window} 条；</li>
 *   <li>若首条不是 {@link UserMessage}，向前扩展到最近的 {@code UserMessage}（保证「一问」起步）；</li>
 *   <li>若窗口内某条 {@link ToolResponseMessage} 的前一条不是携带 toolCalls 的
 *       {@link AssistantMessage}，继续向前保留该 {@code AssistantMessage}，避免产生孤儿工具结果。</li>
 * </ol>
 *
 * <p>返回 {@link UpdatePolicy#REPLACE}：整段替换，而不是追加（追加会越裁越多）。</p>
 */
public class HistoryWindowHook extends MessagesModelHook {

    private static final String HOOK_NAME = "historyWindow";

    private final int window;

    public HistoryWindowHook(int window) {
        this.window = Math.max(1, window);
    }

    @Override
    public String getName() {
        return HOOK_NAME;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        return new AgentCommand(trim(previousMessages), UpdatePolicy.REPLACE);
    }

    /** 纯函数，便于单测；不做任何 IO。 */
    List<Message> trim(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (messages.size() <= window) {
            return List.copyOf(messages);
        }

        int start = messages.size() - window;
        // 规则 2+3 合并：只要「首条不是 UserMessage」或「窗口内有孤儿 ToolResponse」，就继续往前扩一条。
        // start 单调递减，最多走到 0，必然终止。
        while (start > 0
                && (!(messages.get(start) instanceof UserMessage) || hasOrphanToolResponse(messages, start))) {
            start--;
        }

        List<Message> trimmed = new ArrayList<>(messages.subList(start, messages.size()));
        // 极端情况（数据本身缺配对且已无更早的 Assistant 可保留）：丢弃孤儿工具结果，避免模型收到无主的结果
        trimOrphanToolResponses(trimmed);
        return List.copyOf(trimmed);
    }

    /** 窗口 [start, size) 内是否存在没有配对的 ToolResponseMessage。 */
    private static boolean hasOrphanToolResponse(List<Message> messages, int start) {
        for (int i = start + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage && !carriesToolCalls(messages.get(i - 1))) {
                return true;
            }
        }
        return false;
    }

    private static void trimOrphanToolResponses(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage
                    && (i == 0 || !carriesToolCalls(messages.get(i - 1)))) {
                messages.remove(i);
                i--;
            }
        }
    }

    private static boolean carriesToolCalls(Message message) {
        return message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls();
    }
}
