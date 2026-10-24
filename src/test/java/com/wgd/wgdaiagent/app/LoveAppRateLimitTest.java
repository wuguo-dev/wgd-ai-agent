package com.wgd.wgdaiagent.app;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 使用真实 DashScope 客户端和项目重试配置，仅模拟 HTTP 响应；不调用云服务。 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LoveAppRateLimitTest {

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    private static final String RATE_LIMIT =
            "{\"code\":\"Throttling.RateQuota\",\"message\":\"Requests rate limit exceeded\"}";
    private static final String REPORT_RESPONSE = """
            {
              "request_id": "offline-test",
              "output": {"choices": [{"finish_reason": "stop", "message": {
                "role": "assistant",
                "content": "{\\"title\\":\\"Test report\\",\\"suggestions\\":[\\"Take a rest\\"]}"
              }}]},
              "usage": {"input_tokens": 10, "output_tokens": 10, "total_tokens": 20}
            }
            """;

    @TempDir
    Path memoryDirectory;

    // 读取项目 YAML 中的真实策略，只缩短等待时间以保证离线测试快速执行。
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
            .withPropertyValues(
                    "spring.config.location=classpath:/application.yml",
                    "spring.ai.retry.backoff.initial-interval=1ms",
                    "spring.ai.retry.backoff.max-interval=4ms");

    @Test
    void reportSucceedsAfterTemporaryRateLimit() {
        contextRunner.run(context -> {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(ENDPOINT))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON).body(RATE_LIMIT));
            server.expect(requestTo(ENDPOINT))
                    .andRespond(withSuccess(REPORT_RESPONSE, MediaType.APPLICATION_JSON));

            LoveApp.LoveReport report = app(context, builder)
                    .doChatWithReport("Please generate a report", "temporary-rate-limit");

            assertEquals("Test report", report.title());
            assertEquals(1, report.suggestions().size());
            server.verify();
        });
    }

    @Test
    void persistentRateLimitStopsAtConfiguredAttemptLimit() {
        contextRunner.run(context -> {
            int attempts = context.getBean(SpringAiRetryProperties.class).getMaxAttempts();
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(ExpectedCount.times(attempts), requestTo(ENDPOINT))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON).body(RATE_LIMIT));

            LoveApp app = app(context, builder);
            assertThrows(TransientAiException.class,
                    () -> app.doChatWithReport("Please generate a report", "persistent-rate-limit"));
            server.verify();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    void permanentClientErrorsAreNotRetried(int status) {
        contextRunner.run(context -> {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                    .andRespond(withStatus(HttpStatus.valueOf(status))
                            .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Rejected\"}"));

            LoveApp app = app(context, builder);
            assertThrows(NonTransientAiException.class,
                    () -> app.doChatWithReport("Please generate a report", "permanent-error-" + status));
            server.verify();
        });
    }

    private LoveApp app(AssertableApplicationContext context, RestClient.Builder restClientBuilder) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey("offline-test-key")
                .restClientBuilder(restClientBuilder)
                .responseErrorHandler(context.getBean(ResponseErrorHandler.class))
                .build();
        DashScopeChatModel model = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder().model("qwen-turbo").build())
                .retryTemplate(context.getBean(RetryTemplate.class))
                .build();

        // LoveApp 构造时确定记忆目录，让测试会话只写入临时文件夹。
        String originalDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", memoryDirectory.toString());
            return new LoveApp(model);
        } finally {
            System.setProperty("user.dir", originalDirectory);
        }
    }
}
