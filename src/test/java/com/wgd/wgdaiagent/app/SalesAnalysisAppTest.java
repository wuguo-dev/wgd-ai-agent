package com.wgd.wgdaiagent.app;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("live")
@SpringBootTest
class SalesAnalysisAppTest {

    @Resource
    private SalesAnalysisApp salesAnalysisApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮：提供身份和产品背景
        String message = "你好，我是wgd，一家电商公司的运营负责人，我们主推的产品是智能保温杯。";
        String answer = salesAnalysisApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮：提出分析需求，依赖上一轮背景
        message = "我想分析一下这款产品最近三个季度的销售表现，看看和上个季度比怎么样。";
        answer = salesAnalysisApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮：验证多轮记忆
        message = "我们公司主推的产品是什么？我是做什么的？你还记得吗？";
        answer = salesAnalysisApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }
}
