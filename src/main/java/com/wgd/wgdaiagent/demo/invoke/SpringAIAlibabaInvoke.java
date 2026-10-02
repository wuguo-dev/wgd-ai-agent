package com.wgd.wgdaiagent.demo.invoke;

import com.alibaba.dashscope.assistants.Assistant;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;

public class SpringAIAlibabaInvoke implements CommandLineRunner {

    @Resource
    private ChatModel  dashscopeChatModel;


    @Override
    public void run(String... args) throws Exception {
        AssistantMessage output = dashscopeChatModel.call(new Prompt("你好，我是蜘蛛侠"))
                .getResult()
                .getOutput();
        System.out.println(output.getText());
    }
}
