package com.wgd.wgdaiagent.rag;

import cn.hutool.core.lang.UUID;
import com.wgd.wgdaiagent.app.LoveApp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * loadMarkdowns 只验证 classpath:document/*.md 下的 Markdown 确实被解析成了可用的 Document，
 * 它自己 new 了 Loader，不依赖容器。
 * doChatWithRag 走完整链路、需要从容器注入 LoveApp，所以整个类标注了 @SpringBootTest：
 * 容器启动时 LoveAppVectorStoreConfig 会真实调用 embedding 接口，该测试本身也会请求对话模型。
 */
@SpringBootTest
class LoveAppDocumentLoaderTest {


    // 这里用全限定名：本类同时 import 了 org.springframework.core.io.Resource，简名会冲突
    @jakarta.annotation.Resource
    private LoveApp loveApp;

    private static final String LOCATION_PATTERN = "classpath:document/*.md";

    private final ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    private final LoveAppDocumentLoader loveAppDocumentLoader = new LoveAppDocumentLoader(resourcePatternResolver);

    @Test
    void loadMarkdowns() throws IOException {
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();

        assertFalse(documents.isEmpty(), "document 目录下的 Markdown 应能被解析出文档");

        // 读取时通过 additionalMetadata 给每篇文档打上了 filename，用它反查文件是否都加载到了
        Set<String> loadedFiles = documents.stream()
                .map(document -> document.getMetadata().get("filename"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(markdownFilesOnClasspath(), loadedFiles,
                "classpath 上的每个 Markdown 文件都应被加载，且都带上 filename 元数据");

        // 标题会把正文切分成多篇文档，每一篇的正文都不该为空
        assertTrue(documents.stream().allMatch(document -> !document.getText().isBlank()),
                "解析出的文档不应有空白正文");
    }

    /** 期望被加载的文件清单：直接从 classpath 上取，避免把文件名写死在测试里 */
    private Set<String> markdownFilesOnClasspath() throws IOException {
        return Arrays.stream(resourcePatternResolver.getResources(LOCATION_PATTERN))
                .map(Resource::getFilename)
                .collect(Collectors.toSet());
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "我已经结婚了，但是婚后关系不太亲密，怎么办？";
        String answer =  loveApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

}