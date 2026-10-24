package com.wgd.wgdaiagent.app;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("live")
@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;
    @Resource(name = "loveAppVectorStore")
    private VectorStore vectorStore;
    @Autowired
    private List list;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是wgd。我捡到了一只猫，我给他取名叫咖啡！";
        String answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "我想让咖啡能够记住我！";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我捡到的猫叫什么名字？我和你说过，你还记得吗？";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }


    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message="你好，我是wgd，我目前单身，刚分手不久现在好难受啊！";
        LoveApp.LoveReport loveReport= loveApp.doChatWithReport(message,chatId);
        Assertions.assertNotNull(loveReport);
    }

    @Test
    void doChatWithVector() {
        String chatId = UUID.randomUUID().toString();
        List<String> questions = List.of(
                "你好，现在我是单身状态，如何能找到另一半？",
                "如果找到对象该如何相处？",
                "如果吵架了该怎么办？"
        );
        List<Document> documents = new ArrayList<>();

        for (int round = 0; round < questions.size(); round++) {
            String message = questions.get(round);
            // 三轮使用同一个 chatId，保留连续对话上下文。
            String answer = loveApp.doChat(message, chatId);
            Assertions.assertNotNull(answer);
            Assertions.assertFalse(answer.isBlank(), "第 " + (round + 1) + " 轮回复不应为空");

            // 每轮问答保存为一篇文档；元数据便于按会话查找。
            documents.add(new Document(
                    "用户：" + message + "\n助手：" + answer,
                    //用来保存文档的元数据，给文档附加的标签
                    Map.of("chat_id", chatId, "round", round + 1, "source", "chat_test")
            ));
        }

        // 三篇文档一次提交：内部调用 Embedding，再将向量写入 PostgreSQL。
        vectorStore.add(documents);
        System.out.printf("对话向量已保存：chatId=%s，文档数=%d%n", chatId, documents.size());
        // 本例保留入库数据，便于测试结束后在数据库查看 embedding 列。
    }


    @Test
    void doChatWithRag() {
        String chatId =UUID.randomUUID().toString();
        List<String> question =List.of("我已经单身一年了，不知道要不要找对象。"
                ,"另一半能给我带来什么？"
                ,"我现在刚刚找到工作，就你而言你觉得需要找对象吗？");
        for (int round = 0; round < question.size(); round++) {
            String message = question.get(round);
            // 使用同一个chatId
            String answer = loveApp.doChatWithRag(message, chatId);
            Assertions.assertNotNull(answer);
            Assertions.assertFalse(answer.isBlank(),"第" + (round+1) + "轮回复不应为空");

        }
    }

    @Test
    void doChat() {
    }

    @Test
    void testDoChatWithReport() {
    }

    @Test
    void doChatWithRagCloud() {
    }
}
