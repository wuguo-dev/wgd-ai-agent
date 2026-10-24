package com.wgd.wgdaiagent.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.function.LongSupplier;

import static com.wgd.wgdaiagent.security.ApiKeyFilter.API_KEY_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimitFilter} 的离线契约：注入假时钟驱动令牌桶，不 sleep、不依赖真实时间。
 *
 * <p>一次请求消费 1 个令牌，所以「连打 burst 次通过、第 burst+1 次 429」是最直接的断言。</p>
 */
@Tag("offline")
class RateLimitFilterTest {

    private static final String AI_PATH = "/ai/love/agent/chat";

    private static final String RATE_LIMITED_BODY =
            "{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后重试\",\"chatId\":null}";

    @Test
    void allowsBurstThenRejectsAndAdvertisesRetryAfter() throws Exception {
        FakeClock clock = new FakeClock();
        RateLimitFilter filter = new RateLimitFilter(properties("secret", 30, 30), clock);

        for (int i = 0; i < 30; i++) {
            assertPassed(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertRejected(filter, aiRequest("secret", "10.0.0.1"), rejected);
        // 30/分钟 = 0.5 个/秒，补满 1 个要 2 秒（向上取整）
        assertEquals("2", rejected.getHeader("Retry-After"));
    }

    @Test
    void recoversAfterClockAdvancesOneWindow() throws Exception {
        FakeClock clock = new FakeClock();
        // 60/分钟 = 1 个/秒，容量 2 → 拨 2 秒正好回满
        RateLimitFilter filter = new RateLimitFilter(properties("secret", 60, 2), clock);

        assertPassed(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());
        assertPassed(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());
        assertRejected(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());

        clock.advance(Duration.ofSeconds(2));

        assertPassed(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());
        assertPassed(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());
        assertRejected(filter, aiRequest("secret", "10.0.0.1"), new MockHttpServletResponse());
    }

    @Test
    void differentApiKeysUseIndependentBuckets() throws Exception {
        FakeClock clock = new FakeClock();
        RateLimitFilter filter = new RateLimitFilter(properties("secret", 60, 1), clock);

        assertPassed(filter, aiRequest("key-a", "10.0.0.1"), new MockHttpServletResponse());
        assertRejected(filter, aiRequest("key-a", "10.0.0.2"), new MockHttpServletResponse());

        // key-b 的桶是全新的：同 IP、同时间窗也应放行
        assertPassed(filter, aiRequest("key-b", "10.0.0.1"), new MockHttpServletResponse());
    }

    @Test
    void fallsBackToRemoteAddressWhenApiKeyNotConfigured() throws Exception {
        FakeClock clock = new FakeClock();
        RateLimitFilter filter = new RateLimitFilter(properties("", 60, 1), clock);

        assertPassed(filter, aiRequest(null, "10.0.0.1"), new MockHttpServletResponse());
        assertRejected(filter, aiRequest(null, "10.0.0.1"), new MockHttpServletResponse());

        // 换一个来源地址就是另一个桶
        assertPassed(filter, aiRequest(null, "10.0.0.2"), new MockHttpServletResponse());
    }

    @Test
    void nonAiPathsAreNotRateLimited() throws Exception {
        FakeClock clock = new FakeClock();
        RateLimitFilter filter = new RateLimitFilter(properties("", 60, 1), clock);

        for (int i = 0; i < 5; i++) {
            assertPassed(filter, request("/hello", null, "10.0.0.1"), new MockHttpServletResponse());
        }
    }

    private static void assertPassed(RateLimitFilter filter,
                                     MockHttpServletRequest request,
                                     MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), "放行时请求应到达下游过滤器");
    }

    private static void assertRejected(RateLimitFilter filter,
                                       MockHttpServletRequest request,
                                       MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "超限请求不应继续调用下游");
        assertEquals(429, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals(RATE_LIMITED_BODY, response.getContentAsString());

        String retryAfter = response.getHeader("Retry-After");
        assertNotNull(retryAfter, "429 必须带 Retry-After");
        assertTrue(Long.parseLong(retryAfter) >= 1L, "Retry-After 最小 1 秒");
    }

    private static SecurityProperties properties(String apiKey, int perMinute, int burst) {
        SecurityProperties properties = new SecurityProperties();
        properties.setApiKey(apiKey);
        properties.setRateLimitPerMinute(perMinute);
        properties.setRateLimitBurst(burst);
        return properties;
    }

    private static MockHttpServletRequest aiRequest(String providedKey, String remoteAddr) {
        return request(AI_PATH, providedKey, remoteAddr);
    }

    private static MockHttpServletRequest request(String servletPath, String providedKey, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api" + servletPath);
        request.setServletPath(servletPath);
        request.setRemoteAddr(remoteAddr);
        if (providedKey != null) {
            request.addHeader(API_KEY_HEADER, providedKey);
        }
        return request;
    }

    /** 可控单调纳秒时钟：只有显式 {@link #advance(Duration)} 才让时间前进。 */
    private static final class FakeClock implements LongSupplier {

        private long nanos = 1_000_000_000L;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}
