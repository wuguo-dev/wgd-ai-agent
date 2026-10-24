package com.wgd.wgdaiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 百炼云知识库 RAG 配置。
 * 配置 love-app.rag.cloud.index-name 后注册 Advisor，业务侧通过
 * chatClient.prompt().user(message).advisors(loveAppRagCloudAdvisor) 使用。
 */
@Configuration
@Slf4j
public class LoveAppRagCloudAdvisorConfig {

    @Bean
    @ConditionalOnProperty(prefix = "love-app.rag.cloud", name = "index-name")
    public Advisor loveAppRagCloudAdvisor(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${love-app.rag.cloud.workspace-id:}") String workspaceId,
            @Value("${love-app.rag.cloud.index-name}") String indexName) {

        Assert.hasText(apiKey, "请配置 spring.ai.dashscope.api-key");
        Assert.hasText(indexName, "请配置 love-app.rag.cloud.index-name（百炼知识库名称，不是 ID）");

        DashScopeApi.Builder apiBuilder = DashScopeApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl);
        // 未指定时使用默认业务空间；独立业务空间需要填写所属空间 ID。
        if (StringUtils.hasText(workspaceId)) {
            apiBuilder.workSpaceId(workspaceId);
        }

        DashScopeDocumentRetriever retriever = new DashScopeDocumentRetriever(
                apiBuilder.build(),
                DashScopeDocumentRetrieverOptions.builder()
                        // 此检索器按知识库名称查询云端索引。
                        .indexName(indexName)
                        .rerankTopN(5)
                        .build());

        log.info("配置百炼云知识库 RAG Advisor，知识库：{}", indexName);
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
    }
}
