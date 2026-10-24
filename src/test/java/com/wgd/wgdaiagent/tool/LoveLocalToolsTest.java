package com.wgd.wgdaiagent.tool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1/T2/T3（规格 §9.3）：本地工具全离线用例，无 Spring 上下文、无网络、无磁盘。
 */
@Tag("offline")
class LoveLocalToolsTest {

    private static final Pattern SHANGHAI_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2} \\w+ \\(Asia/Shanghai\\)");

    private static final Pattern ENTRY_PREFIX = Pattern.compile(
            "^\\d+\\. \\[filename=.+ score=\\d+\\.\\d{2}] .*$");

    // ---------- T1 currentTime ----------

    @Test
    void currentTimeFallsBackToShanghaiWhenTimeZoneMissing() {
        LoveLocalTools tools = new LoveLocalTools(provider(null));

        String result = tools.currentTime(null);

        assertTrue(SHANGHAI_TIME.matcher(result).matches(), "默认时区输出应匹配 ISO 偏移 + 英文星期：" + result);
        assertWeekdayMatchesIsoPart(result);
    }

    @Test
    void currentTimeTreatsBlankTimeZoneAsDefault() {
        LoveLocalTools tools = new LoveLocalTools(provider(null));

        assertTrue(SHANGHAI_TIME.matcher(tools.currentTime("   ")).matches());
    }

    @Test
    void currentTimeHonoursExplicitTimeZone() {
        LoveLocalTools tools = new LoveLocalTools(provider(null));

        String result = tools.currentTime("America/New_York");

        assertTrue(result.endsWith("(America/New_York)"), result);
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2} \\w+ \\(America/New_York\\)"),
                result);
        assertWeekdayMatchesIsoPart(result);
    }

    @Test
    void currentTimeDegradesOnIllegalTimeZoneWithoutThrowing() {
        LoveLocalTools tools = new LoveLocalTools(provider(null));

        String result = tools.currentTime("Mars/Olympus_Mons");

        assertTrue(result.startsWith("时区 \"Mars/Olympus_Mons\" 无效，已使用 Asia/Shanghai："), result);
        assertTrue(result.endsWith("(Asia/Shanghai)"), result);
    }

    // ---------- T2 loveKbSearch 正常路径 ----------

    @Test
    void loveKbSearchPassesFixedSearchRequestAndFormatsHits() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(List.of(
                document("建议保持固定的沟通节奏，例如每周至少一次视频通话。", "恋爱篇.md", 0.83d),
                document("先确认双方对未来城市的规划是否一致，再讨论结束异地的时间点。", "已婚篇.md", 0.61d)));
        LoveLocalTools tools = new LoveLocalTools(provider(vectorStore));

        String result = tools.loveKbSearch("异地恋怎么维持");

        SearchRequest request = vectorStore.lastRequest;
        assertEquals("异地恋怎么维持", request.getQuery(), "query 应原样透传");
        assertEquals(0.5d, request.getSimilarityThreshold(), 1e-9, "相似度阈值固定 0.5");
        assertEquals(5, request.getTopK(), "topK 固定 5");

        List<String> entries = result.lines().toList();
        assertEquals(2, entries.size());
        assertEquals("1. [filename=恋爱篇.md score=0.83] 建议保持固定的沟通节奏，例如每周至少一次视频通话。",
                entries.get(0));
        assertEquals("2. [filename=已婚篇.md score=0.61] 先确认双方对未来城市的规划是否一致，再讨论结束异地的时间点。",
                entries.get(1));
    }

    @Test
    void loveKbSearchCapsAtFiveEntriesAndTruncatesLongText() {
        List<Document> documents = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            documents.add(document("内容" + i, "doc" + i + ".md", 0.9d - i * 0.01d));
        }
        String longText = "长".repeat(LoveLocalTools.KB_MAX_SNIPPET_CHARS + 200);
        documents.add(document(longText, "长文.md", 0.5d));

        LoveLocalTools tools = new LoveLocalTools(provider(new RecordingVectorStore(documents)));

        List<String> entries = tools.loveKbSearch("q").lines().toList();

        assertEquals(LoveLocalTools.KB_TOP_K, entries.size(), "最多 5 条");
        for (String entry : entries) {
            assertTrue(ENTRY_PREFIX.matcher(entry).matches(), "编号/来源/分数格式固定：" + entry);
            String body = entry.substring(entry.indexOf("] ") + 2);
            assertTrue(body.length() <= LoveLocalTools.KB_MAX_SNIPPET_CHARS,
                    "单条正文不超过 " + LoveLocalTools.KB_MAX_SNIPPET_CHARS + " 字符，实际 " + body.length());
        }
    }

    @Test
    void loveKbSearchReportsEmptyResult() {
        LoveLocalTools tools = new LoveLocalTools(provider(new RecordingVectorStore(List.of())));

        assertEquals(LoveLocalTools.KB_EMPTY, tools.loveKbSearch("完全无关的问题"));
    }

    // ---------- T3 loveKbSearch 降级路径 ----------

    @Test
    void loveKbSearchDegradesWhenVectorStoreMissing() {
        LoveLocalTools tools = new LoveLocalTools(provider(null));

        assertEquals(LoveLocalTools.KB_UNAVAILABLE, tools.loveKbSearch("异地恋怎么维持"));
    }

    @Test
    void loveKbSearchDegradesWhenVectorStoreLookupThrows() {
        LoveLocalTools tools = new LoveLocalTools(new ObjectProvider<>() {
            @Override
            public VectorStore getIfAvailable() {
                throw new IllegalStateException("datasource closed");
            }
        });

        assertEquals(LoveLocalTools.KB_UNAVAILABLE, tools.loveKbSearch("异地恋怎么维持"));
    }

    @Test
    void loveKbSearchDegradesWhenSimilaritySearchThrows() {
        LoveLocalTools tools = new LoveLocalTools(provider(new FailingVectorStore(
                new IllegalStateException("jdbc:postgresql://secret-host/db password=secret"))));

        String result = tools.loveKbSearch("异地恋怎么维持");

        assertTrue(result.startsWith("恋爱知识库检索失败（IllegalStateException）"), result);
        assertTrue(result.endsWith("请基于通用经验回答。"), result);
        assertFalse(result.contains("secret"), "异常 message 不得泄漏到回喂文本");
        assertFalse(result.contains("jdbc"), "SQL/URL 不得泄漏到回喂文本");
    }

    // ---------- 测试替身 ----------

    private static void assertWeekdayMatchesIsoPart(String result) {
        String isoPart = result.substring(0, result.indexOf(' '));
        OffsetDateTime parsed = OffsetDateTime.parse(isoPart);
        DayOfWeek dayOfWeek = parsed.getDayOfWeek();
        String expected = " " + dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " (";
        assertTrue(result.contains(expected), "星期应与 ISO 日期一致，期望包含「" + expected + "」：" + result);
    }

    private static Document document(String text, String filename, Double score) {
        return Document.builder()
                .text(text)
                .metadata(Map.of("filename", filename))
                .score(score)
                .build();
    }

    /** 只覆写 getIfAvailable：ObjectProvider 的其余方法都有默认实现。 */
    private static ObjectProvider<VectorStore> provider(VectorStore vectorStore) {
        return new ObjectProvider<>() {
            @Override
            public VectorStore getIfAvailable() {
                return vectorStore;
            }
        };
    }

    /** 假 VectorStore：记录收到的 SearchRequest，返回固定文档（§9.5）。 */
    private static class RecordingVectorStore implements VectorStore {

        private final List<Document> documents;

        private SearchRequest lastRequest;

        private RecordingVectorStore(List<Document> documents) {
            this.documents = documents;
        }

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
            this.lastRequest = request;
            return documents;
        }
    }

    /** 假 VectorStore：检索必抛，用于验证异常兜底。 */
    private static final class FailingVectorStore extends RecordingVectorStore {

        private final RuntimeException failure;

        private FailingVectorStore(RuntimeException failure) {
            super(List.of());
            this.failure = failure;
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            throw failure;
        }
    }
}
