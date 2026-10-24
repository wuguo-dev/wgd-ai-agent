package com.wgd.wgdaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 自定义日志 Advisor
 * 打印 info 级别日志，<b>只打字符数不打正文</b>（规格 §7.4：用户消息与答复正文属隐私）
 */
@Slf4j
public class MyLoggerAdvisor implements BaseAdvisor {

    private final int order;

    public MyLoggerAdvisor() {
        // 与 SimpleLoggerAdvisor 保持一致：order=0 排在记忆 Advisor 内层，
        // 所以日志里看到的是拼好历史消息之后、真正要发给模型的请求
        this(0);
    }

    public MyLoggerAdvisor(int order) {
        this.order = order;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        logRequest(request.prompt());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        logResponse(response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 流式响应是一个个 chunk 推下来的，若沿用默认实现，after 会被调用 N 次、日志被打印 N 遍。
        // 交给 ChatClientMessageAggregator 聚合成一条完整回复后再打印（仍然只打长度）。
        return Mono.just(request)
                .publishOn(this.getScheduler())
                .map(req -> this.before(req, chain))
                .flatMapMany(chain::nextStream)
                .transform(responses -> new ChatClientMessageAggregator()
                        .aggregateChatClientResponse(responses, this::logResponse));
    }

    /** 只记录最后一条用户消息的字符数（没有用户消息时明确标注） */
    private void logRequest(Prompt prompt) {
        List<UserMessage> userMessages = prompt.getUserMessages();
        if (userMessages.isEmpty()) {
            log.info("AI Request: <无用户消息>");
            return;
        }
        String text = userMessages.get(userMessages.size() - 1).getText();
        log.info("AI Request: userChars={}", text == null ? 0 : text.length());
    }

    private void logResponse(ChatClientResponse response) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        // 异常或空响应时 getResult() 可能为 null，日志不该把主流程带崩
        if (chatResponse == null || chatResponse.getResult() == null) {
            log.info("AI Response: <空响应>");
            return;
        }
        String text = chatResponse.getResult().getOutput().getText();
        log.info("AI Response: answerChars={}", text == null ? 0 : text.length());
    }
}
