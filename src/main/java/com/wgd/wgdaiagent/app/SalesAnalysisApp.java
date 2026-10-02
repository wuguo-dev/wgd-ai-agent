package com.wgd.wgdaiagent.app;

import com.wgd.wgdaiagent.chatmemory.FileBasedChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 销售数据分析顾问应用。
 * 当前为基础版：多轮对话 + 文件持久化记忆。
 * 后续扩展方向：结构化销售报告（.entity()）、@Tool 数据查询工具（模拟数据）、RAG 知识库。
 */
@Component
@Slf4j
public class SalesAnalysisApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "扮演深耕销售数据分析领域的顾问。开场向用户表明身份，并说明可以协助分析销售趋势、产品表现和客户构成。" +
            "围绕以下信息向用户提问：想分析的对象或指标（如销售额、销量、转化率）；数据范围（整体、某条业务线或某个产品）；" +
            "时间区间（如最近三个月）；以及对比目标（同比、环比或指定基准）。信息不全时先追问补齐，不要凭空编造数据。" +
            "收集齐后先复述确认分析目标，再给出针对性的分析思路和结论。";

    public SalesAnalysisApp(ChatModel dashscopeChatModel) {
        // 基于文件持久化的对话记忆：kryo 序列化，每个会话一个 .kryo 文件，重启不丢
        // 单独放在 chat-memory/sales-analysis/ 子目录，与 LoveApp 的会话文件隔离
        ChatMemory chatMemory = new FileBasedChatMemory(
                System.getProperty("user.dir") + "/chat-memory/sales-analysis");

        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                        // new ReReadingAdvisor() // 后续需要增强分析推理时再开启
                )
                .build();
    }

    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("销售分析回复：{}", content);
        return content;
    }
}
