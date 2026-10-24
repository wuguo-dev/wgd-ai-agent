package com.wgd.wgdaiagent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 自定义 Re2 Advisor
 * 可提高大型语言模型的推理能力
 */
public class ReReadingAdvisor implements BaseAdvisor {

    private final int order;

    /**
     * 默认紧贴在记忆 Advisor 里侧。
     * <p>
     * 关键：order 越小越靠外，而 {@code before()} 是<b>由外向内</b>依次执行的。
     * 所以本 Advisor 的 order 要落在这个区间里：
     * <pre>
     *   MessageChatMemoryAdvisor(-2147482648)  &lt;  Re2  &lt;  SimpleLoggerAdvisor(0)
     * </pre>
     * <ul>
     *   <li><b>比记忆 Advisor 大</b> —— 排在它内层。记忆在 before() 里就把用户的干净原话存进去了，
     *       Re2 在其后改写，复读文本只发给模型、不写进历史，不会逐轮叠加；</li>
     *   <li><b>比日志 Advisor 小</b> —— 排在它外层。这样日志的 before() 在 Re2 之后执行，
     *       打出来的才是真正发给模型的复读版 prompt。若反过来，日志拍到的是复读前的原文，
     *       会让人误以为 Re2 没生效。</li>
     * </ul>
     * 默认取记忆 Advisor 之后一格，即"尽可能靠外、但不越过记忆"。
     */
    public ReReadingAdvisor() {
        this(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1);
    }

    /** 需要精确控制与其他 Advisor 的相对位置时用这个构造器 */
    public ReReadingAdvisor(int order) {
        this.order = order;
    }

    /**
     * Re2 模板：同一个问题放两遍，第二遍用 "Read the question again:" 引导模型重读。
     * 两个 %s 都会被替换成用户的原话，所以问题实际出现了两次。
     */
    private static final String RE2_TEMPLATE = """
            %s
            Read the question again: %s
            """;

    /**
     * 请求发给模型之前调用，在这里把用户提示词改写为 Re2 格式。
     * 只改请求、不改响应，所以不需要额外调用一次模型。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 0. 没有用户消息时（例如纯 system 调用）Re2 无从谈起，原样放行。
        //    不加这道判断的话，augmentUserMessage 会往消息末尾塞一条空的 UserMessage，
        //    等于给模型发一句没头没尾的 "Read the question again:"
        if (request.prompt().getUserMessages().isEmpty()) {
            return request;
        }

        // 1. 取出当前 Prompt，把最后一条用户消息的文本替换为 Re2 模板
        Prompt re2Prompt = request.prompt().augmentUserMessage(userMessage -> userMessage
                // mutate() 基于原消息构造，会自动带上原有的 metadata 和 media，不会丢
                .mutate()
                // 用户的原话填进模板的两个 %s
                .text(RE2_TEMPLATE.formatted(userMessage.getText(), userMessage.getText()))
                .build());

        // 2. 用改写后的 Prompt 重建请求
        //    mutate() 是浅拷贝，会连 context（含会话 ID）一起带过去，记忆功能不受影响
        return request.mutate().prompt(re2Prompt).build();
    }

    /**
     * 响应返回之后调用。本 Advisor 不改响应，原样返回即可，
     * 但 BaseAdvisor 接口要求必须实现这个方法。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    // getName() 不用重写：BaseAdvisor 的默认实现就是返回 this.getClass().getSimpleName()
}
