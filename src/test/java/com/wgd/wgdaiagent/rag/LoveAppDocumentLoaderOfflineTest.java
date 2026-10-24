package com.wgd.wgdaiagent.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
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
 * 知识库文档加载的离线回归（原 LoveAppDocumentLoaderTest 的 loadMarkdowns 部分，规格 §9.1 例外条）。
 * 自己 new Loader 读 classpath:document/*.md，不启动 Spring 上下文、不联网，
 * 所以拆到本类并标 @Tag("offline")，让文档回归留在默认门槛 `mvn -o test` 里。
 * 需要真模型的 doChatWithRag 仍留在 LoveAppDocumentLoaderTest（@Tag("live")）。
 */
@Tag("offline")
class LoveAppDocumentLoaderOfflineTest {

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

}
