package com.wgd.wgdaiagent.rag;

import cn.hutool.core.lang.UUID;
import com.wgd.wgdaiagent.app.LoveApp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * doChatWithRag 走完整链路、需要从容器注入 LoveApp，所以本类保留 @SpringBootTest：
 * 容器启动时 LoveAppVectorStoreConfig 会真实调用 embedding 接口，该测试本身也会请求对话模型。
 * 纯离线的 loadMarkdowns 已拆到 LoveAppDocumentLoaderOfflineTest（@Tag("offline")，规格 §9.1 例外条）。
 */
@Tag("live")
@SpringBootTest
class LoveAppDocumentLoaderTest {


    @jakarta.annotation.Resource
    private LoveApp loveApp;

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "我已经结婚了，但是婚后关系不太亲密，怎么办？";
        String answer =  loveApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

}
