package com.wgd.wgdaiagent.app;

import com.wgd.wgdaiagent.rag.QueryRewriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T28（规格 §9.3）：{@code doChatWithRag} 收敛为单检索，且 {@code LoveApp} 构造器不变。
 *
 * <p>假 {@link VectorStore} 记录 {@code similaritySearch} 次数，假 {@link ChatModel} 不联网。</p>
 */
@Tag("offline")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LoveAppRagSingleRetrievalTest {

    @Test
    void doChatWithRagTriggersExactlyOneSimilaritySearch(@TempDir Path tempDir) {
        CountingVectorStore vectorStore = new CountingVectorStore();
        StubChatModel chatModel = new StubChatModel("建议先固定每周的视频通话节奏。");
        LoveApp loveApp = newLoveApp(chatModel, tempDir);
        ReflectionTestUtils.setField(loveApp, "pgVectorVectorStore", vectorStore);
        // 查询重写也是真实现，用同一个假模型即可
        ReflectionTestUtils.setField(loveApp, "queryRewriter", new QueryRewriter(chatModel));

        String answer = loveApp.doChatWithRag("异地恋怎么维持", "chat-rag-1");

        assertEquals(1, vectorStore.searchCount,
                "单检索：一次 doChatWithRag 只允许触发一次 similaritySearch（原实现是双检索）");
        assertFalse(answer.isBlank(), "应返回模型答复");
    }

    @Test
    void constructorIsStillSingleChatModelArgument() {
        assertNotNull(assertDoesNotThrowGetConstructor(), "LoveApp(ChatModel) 构造器必须保留（LoveAppRateLimitTest 依赖它）");
        assertEquals(1, LoveApp.class.getDeclaredConstructors().length, "不应新增构造器");
    }

    // ---------- helpers ----------

    /** 构造时把会话记忆目录指到 {@link TempDir}，避免写仓库根的 chat-memory/。 */
    private static LoveApp newLoveApp(ChatModel chatModel, Path tempDir) {
        String originalDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            return new LoveApp(chatModel);
        } finally {
            System.setProperty("user.dir", originalDirectory);
        }
    }

    private static java.lang.reflect.Constructor<LoveApp> assertDoesNotThrowGetConstructor() {
        try {
            return LoveApp.class.getDeclaredConstructor(ChatModel.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("LoveApp(ChatModel) 构造器丢了", e);
        }
    }

    /** 假 ChatModel：固定答复，不联网。 */
    private static final class StubChatModel implements ChatModel {

        private final String answer;

        private StubChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    /** 假 VectorStore：记录检索次数并返回固定文档。 */
    private static final class CountingVectorStore implements VectorStore {

        private int searchCount;

        @Override
        public void add(List<Document> documents) {
            throw new UnsupportedOperationException("离线用例不写向量库");
        }

        @Override
        public void delete(List<String> ids) {
            throw new UnsupportedOperationException("离线用例不删向量库");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            throw new UnsupportedOperationException("离线用例不删向量库");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            searchCount++;
            return List.of(Document.builder()
                    .text("异地恋要保持固定的沟通节奏，并约定结束异地的时间点。")
                    .metadata(Map.of("filename", "恋爱篇.md"))
                    .score(0.82d)
                    .build());
        }
    }
}
