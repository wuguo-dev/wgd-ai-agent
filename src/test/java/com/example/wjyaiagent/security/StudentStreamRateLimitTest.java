package com.example.wjyaiagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StudentStreamRateLimitTest {
    @Test
    void changingApiKeyCannotBypassStudentIpBucket() throws Exception {
        var properties = new SecurityProperties();
        properties.setApiKey("server-secret");
        properties.setRateLimitBurst(1);
        properties.setRateLimitPerMinute(0);
        var filter = new RateLimitFilter(properties, System::nanoTime);
        for (int attempt = 0; attempt < 2; attempt++) {
            var request =
                    new MockHttpServletRequest("POST", "/agent/conversations/id/messages/stream");
            request.setServletPath("/agent/conversations/id/messages/stream");
            request.addHeader("X-API-Key", "arbitrary-" + attempt);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(attempt == 0 ? 200 : 429);
        }
    }
}
