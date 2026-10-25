package com.example.wjyaiagent.service;

import com.example.wjyaiagent.exception.KnowledgeBaseErrorCode;
import com.example.wjyaiagent.exception.KnowledgeBaseException;
import com.example.wjyaiagent.model.Citation;
import com.example.wjyaiagent.model.KnowledgeChatRequest;
import com.example.wjyaiagent.model.KnowledgeChatResponse;
import com.example.wjyaiagent.model.KnowledgeStreamEvent;
import com.example.wjyaiagent.rag.CitingDocumentRetriever;
import com.example.wjyaiagent.rag.QueryRewriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Slf4j
@Service
public class KnowledgeChatService {

    private static final Pattern CHAT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String INVALID_CHAT_ID_MESSAGE = "chatId 只允许 1-64 位字母、数字、下划线或短横线";
    private static final String EVIDENCE_INSUFFICIENT_MESSAGE = "当前知识库没有足够依据回答该问题，请补充更具体的问题或导入相关文档。";

    private final ChatClient chatClient;
    private final QueryRewriter queryRewriter;
    private final CitingDocumentRetriever documentRetriever;

    public KnowledgeChatService(ChatClient knowledgeBaseChatClient,
                                QueryRewriter queryRewriter,
                                ObjectProvider<CitingDocumentRetriever> documentRetriever) {
        this.chatClient = knowledgeBaseChatClient;
        this.queryRewriter = queryRewriter;
        this.documentRetriever = documentRetriever.getIfAvailable();
    }

    public KnowledgeChatResponse chat(KnowledgeChatRequest request) {
        String chatId = normalizeChatId(request.chatId());
        long startedAt = System.nanoTime();
        try {
            String rewritten = queryRewriter.doQueryRewrite(request.message());
            List<Document> documents = retrieve(rewritten, request.message());
            if (documentRetriever != null && documents.isEmpty()) {
                return new KnowledgeChatResponse(chatId, EVIDENCE_INSUFFICIENT_MESSAGE,
                        elapsedMillis(startedAt), List.of());
            }
            String answer = chatClient.prompt()
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                    .user(request.message())
                    .call().content();
            return new KnowledgeChatResponse(chatId, cleanAnswer(answer),
                    elapsedMillis(startedAt), citations(documents));
        } catch (RuntimeException error) {
            throw translate(error);
        }
    }

    public Flux<KnowledgeStreamEvent> stream(KnowledgeChatRequest request) {
        String chatId = normalizeChatId(request.chatId());
        long startedAt = System.nanoTime();
        try {
            String rewritten = queryRewriter.doQueryRewrite(request.message());
            List<Document> documents = retrieve(rewritten, request.message());
            List<Citation> citations = citations(documents);
            if (documentRetriever != null && documents.isEmpty()) {
                return Flux.just(
                        KnowledgeStreamEvent.delta(chatId, EVIDENCE_INSUFFICIENT_MESSAGE),
                        KnowledgeStreamEvent.done(chatId, elapsedMillis(startedAt), citations));
            }
            AtomicBoolean sourceSection = new AtomicBoolean();
            return chatClient.prompt()
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                    .user(request.message()).stream().content()
                    .filter(StringUtils::hasText)
                    .map(delta -> {
                        if (sourceSection.get()) return "";
                        int marker = searchSourceMarker(delta);
                        if (marker >= 0) {
                            sourceSection.set(true);
                            return delta.substring(0, marker);
                        }
                        return delta;
                    })
                    .filter(StringUtils::hasText)
                    .map(delta -> KnowledgeStreamEvent.delta(chatId, delta))
                    .concatWith(Flux.just(KnowledgeStreamEvent.done(chatId, elapsedMillis(startedAt), citations)))
                    .onErrorResume(error -> {
                        KnowledgeBaseException translated = translate(error);
                        return Flux.just(KnowledgeStreamEvent.error(
                                chatId, translated.getCode().name(), translated.getMessage()));
                    });
        } catch (RuntimeException error) {
            KnowledgeBaseException translated = translate(error);
            return Flux.just(KnowledgeStreamEvent.error(chatId, translated.getCode().name(), translated.getMessage()));
        }
    }

    private List<Document> retrieve(String rewritten, String original) {
        if (documentRetriever == null) return List.of();
        return documentRetriever.prepare(rewritten, original);
    }

    private static List<Citation> citations(List<Document> documents) {
        return documents.isEmpty() ? List.of() : CitingDocumentRetriever.citations(documents);
    }

    static String normalizeChatId(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        String normalized = chatId.trim();
        if (!CHAT_ID_PATTERN.matcher(normalized).matches()) {
            throw new KnowledgeBaseException(KnowledgeBaseErrorCode.INVALID_REQUEST, INVALID_CHAT_ID_MESSAGE);
        }
        return normalized;
    }

    private static KnowledgeBaseException translate(Throwable error) {
        if (error instanceof KnowledgeBaseException exception) return exception;
        if (error instanceof TransientAiException) {
            return new KnowledgeBaseException(KnowledgeBaseErrorCode.AI_UNAVAILABLE,
                    "模型服务暂时不可用，请稍后重试", error);
        }
        if (error instanceof NonTransientAiException) {
            return new KnowledgeBaseException(KnowledgeBaseErrorCode.AI_ERROR,
                    "模型服务调用失败，请稍后重试", error);
        }
        return new KnowledgeBaseException(KnowledgeBaseErrorCode.INTERNAL_ERROR,
                "知识库问答服务内部错误，请稍后重试", error);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    static String cleanAnswer(String answer) {
        if (answer == null) return "";
        int marker = searchSourceMarker(answer);
        return marker < 0 ? answer.trim() : answer.substring(0, marker).trim();
    }

    private static int searchSourceMarker(String answer) {
        String lower = answer.toLowerCase(java.util.Locale.ROOT);
        int best = -1;
        for (String marker : List.of("\n来源", "\n**来源**", "\n引用", "\n参考来源")) {
            int index = lower.indexOf(marker.toLowerCase(java.util.Locale.ROOT));
            if (index >= 0 && (best < 0 || index < best)) best = index;
        }
        return best;
    }
}
