package com.wgd.wgdaiagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地工具（规格 §5.1）的两个 {@link Tool} 方法。方法名即工具名，模型看到的就是
 * {@code currentTime} / {@code loveKbSearch}。
 *
 * <p>不持有会话状态；任何失败都转成中文提示文本回喂模型，<b>不抛异常</b>，保证工具循环不断（D7）。</p>
 */
@Slf4j
@Component
public class LoveLocalTools {

    /** 默认时区：空/空白入参都用它。 */
    static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";

    /** 知识库没有匹配结果时的固定文案。 */
    static final String KB_EMPTY = "知识库中未找到与该问题相关的内容。";

    /** 知识库 Bean 缺失时的固定文案。 */
    static final String KB_UNAVAILABLE = "恋爱知识库当前不可用，请基于通用经验回答并说明这一限制。";

    /** 检索参数（规格 §5.1 T-LOCAL-2 写死）。 */
    static final double KB_SIMILARITY_THRESHOLD = 0.5d;

    static final int KB_TOP_K = 5;

    /** 单条正文最多保留的字符数。 */
    static final int KB_MAX_SNIPPET_CHARS = 500;

    private static final String FILENAME_KEY = "filename";

    private static final String UNKNOWN_FILENAME = "unknown";

    /** 「知识库不可用」WARN 的最小间隔。 */
    private static final long UNAVAILABLE_WARN_INTERVAL_MS = 60_000L;

    /** 2026-10-24T18:11:46+08:00 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /** 上次「知识库不可用」WARN 的时间戳，仅用于日志限流，与会话状态无关。 */
    private volatile long lastUnavailableWarnAt;

    public LoveLocalTools(ObjectProvider<VectorStore> vectorStoreProvider) {
        // 缺失时 getIfAvailable() 返回 null，不炸启动
        this.vectorStoreProvider = vectorStoreProvider;
    }

    /**
     * 回答「今天几号 / 现在几点 / 星期几 / 某时区现在几点」，免网络。
     *
     * @param timeZone IANA 时区（如 {@code Asia/Shanghai}）；空/空白/非法都回落到默认时区且不抛异常
     */
    @Tool(description = "查询当前日期、时间和星期。回答“现在几点/今天几号/星期几/某个时区现在几点”之前必须先调用本工具，"
            + "不要自行推算日期。入参 timeZone 可选，IANA 时区名，如 Asia/Shanghai、America/New_York，留空默认 Asia/Shanghai。")
    public String currentTime(
            @ToolParam(required = false, description = "IANA 时区名，如 Asia/Shanghai；留空表示 Asia/Shanghai")
            String timeZone) {
        String requested = isBlank(timeZone) ? DEFAULT_TIME_ZONE : timeZone.trim();
        try {
            return format(ZonedDateTime.now(ZoneId.of(requested)));
        } catch (RuntimeException e) {
            String fallback = format(ZonedDateTime.now(ZoneId.of(DEFAULT_TIME_ZONE)));
            return "时区 \"" + requested + "\" 无效，已使用 " + DEFAULT_TIME_ZONE + "：" + fallback;
        }
    }

    /**
     * 检索本地恋爱知识库（pgvector），由模型自主决定何时调用。
     *
     * @param query 自然语言问题，如「异地恋怎么维持」
     */
    @Tool(description = "检索本地恋爱知识库（单身/恋爱/已婚三类常见问题与建议）。涉及情感、沟通、心理、相处模式的具体问题时调用；"
            + "地点、天气、路线、距离不要用本工具。入参 query 是用户的自然语言问题。")
    public String loveKbSearch(
            @ToolParam(description = "用户的自然语言问题，例如「异地恋怎么维持」") String query) {
        if (isBlank(query)) {
            return KB_EMPTY;
        }

        VectorStore vectorStore;
        try {
            vectorStore = vectorStoreProvider == null ? null : vectorStoreProvider.getIfAvailable();
        } catch (RuntimeException e) {
            // Bean 创建本身失败（如向量库连不上）也按「不可用」降级
            warnUnavailableOncePerMinute(e.getClass().getSimpleName());
            return KB_UNAVAILABLE;
        }
        if (vectorStore == null) {
            warnUnavailableOncePerMinute("VectorStore Bean 缺失");
            return KB_UNAVAILABLE;
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .similarityThreshold(KB_SIMILARITY_THRESHOLD)
                    .topK(KB_TOP_K)
                    .build());
            if (documents == null || documents.isEmpty()) {
                return KB_EMPTY;
            }
            List<String> entries = new ArrayList<>(Math.min(documents.size(), KB_TOP_K));
            for (Document document : documents) {
                if (entries.size() >= KB_TOP_K) {
                    break;
                }
                entries.add(formatEntry(entries.size() + 1, document));
            }
            return String.join("\n", entries);
        } catch (RuntimeException e) {
            // 只记异常类型：消息里可能有 SQL/URL，按 §7.4 脱敏
            log.warn("恋爱知识库检索失败：{}", e.getClass().getSimpleName());
            return "恋爱知识库检索失败（" + e.getClass().getSimpleName() + "），请基于通用经验回答。";
        }
    }

    /** {@code 1. [filename=<来源> score=0.83] <正文，最多 500 字符>} */
    private static String formatEntry(int index, Document document) {
        String filename = UNKNOWN_FILENAME;
        Object filenameValue = document.getMetadata() == null ? null : document.getMetadata().get(FILENAME_KEY);
        if (filenameValue != null && !filenameValue.toString().isBlank()) {
            filename = filenameValue.toString();
        }
        double score = document.getScore() == null ? 0d : document.getScore();
        return index + ". [filename=" + filename + " score=" + String.format(Locale.ROOT, "%.2f", score) + "] "
                + snippet(document.getText());
    }

    /** 正文压成单行并截断到 {@link #KB_MAX_SNIPPET_CHARS} 个字符，避免一条记录占多行。 */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= KB_MAX_SNIPPET_CHARS) {
            return normalized;
        }
        return normalized.substring(0, KB_MAX_SNIPPET_CHARS);
    }

    /** {@code 2026-10-24T18:11:46+08:00 Saturday (Asia/Shanghai)}：ISO 偏移 + 英文星期，免得模型自己换算。 */
    private static String format(ZonedDateTime dateTime) {
        ZonedDateTime truncated = dateTime.truncatedTo(ChronoUnit.SECONDS);
        return TIME_FORMATTER.format(truncated)
                + " " + truncated.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " (" + truncated.getZone().getId() + ")";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 知识库不可用只 WARN 一次/分钟（§7.2），避免每轮对话刷屏。 */
    private void warnUnavailableOncePerMinute(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastUnavailableWarnAt >= UNAVAILABLE_WARN_INTERVAL_MS) {
            lastUnavailableWarnAt = now;
            log.warn("恋爱知识库不可用（{}），已返回降级文本", reason);
        }
    }
}
