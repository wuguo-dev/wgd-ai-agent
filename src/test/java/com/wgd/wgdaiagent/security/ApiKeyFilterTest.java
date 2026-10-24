package com.wgd.wgdaiagent.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.wgd.wgdaiagent.security.ApiKeyFilter.API_KEY_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ApiKeyFilter} 的离线契约（不起 Spring 上下文）：只用 Mock 请求/响应 + MockFilterChain。
 *
 * <p>{@code context-path=/api} 已由容器剥离，所以这里设置的 servletPath 是 {@code /ai/...}。</p>
 */
@Tag("offline")
class ApiKeyFilterTest {

    private static final String AI_PATH = "/ai/love/agent/chat";

    private static final String SECRET = "s3cr3t-key";

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"缺少或无效的 API Key\",\"chatId\":null}";

    @Test
    void passesAiPathWhenApiKeyNotConfigured() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(properties(""));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertPassed(filter, request(AI_PATH, null), response);
        assertEquals(200, response.getStatus(), "未配置 key 时不应改写状态码");
    }

    @Test
    void rejectsAiPathWithoutHeader() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(properties(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertRejected(filter, request(AI_PATH, null), response);
    }

    @Test
    void rejectsAiPathWithWrongHeader() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(properties(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertRejected(filter, request(AI_PATH, "wrong-key"), response);
    }

    @Test
    void acceptsAiPathWithMatchingHeader() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(properties(SECRET));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertPassed(filter, request(AI_PATH, SECRET), response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hello", "/v3/api-docs", "/swagger-ui.html", "/ai-foo"})
    void nonAiPathsAlwaysPassEvenWithoutHeader(String servletPath) throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(properties(SECRET));

        assertPassed(filter, request(servletPath, null), new MockHttpServletResponse());
    }

    private static void assertPassed(ApiKeyFilter filter,
                                     MockHttpServletRequest request,
                                     MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), "放行时请求应到达下游过滤器");
    }

    private static void assertRejected(ApiKeyFilter filter,
                                       MockHttpServletRequest request,
                                       MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "拒绝时不应继续调用下游");
        assertEquals(401, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals(UNAUTHORIZED_BODY, response.getContentAsString());
    }

    private static SecurityProperties properties(String apiKey) {
        SecurityProperties properties = new SecurityProperties();
        properties.setApiKey(apiKey);
        return properties;
    }

    private static MockHttpServletRequest request(String servletPath, String providedKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api" + servletPath);
        request.setServletPath(servletPath);
        if (providedKey != null) {
            request.addHeader(API_KEY_HEADER, providedKey);
        }
        return request;
    }
}
