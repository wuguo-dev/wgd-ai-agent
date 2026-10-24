package com.wgd.wgdaiagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 可选 API Key 鉴权：只在 {@code love-app.security.api-key} 非空时生效。
 *
 * <p>作用域只到 {@code /ai/**}（{@code server.servlet.context-path=/api} 已被容器剥离，
 * 这里看到的 servletPath 是 {@code /ai/...}）；{@code /hello}、{@code /v3/api-docs}、
 * Swagger UI 等一律放行，避免把接口文档也锁死。</p>
 *
 * <p>比较用 {@link MessageDigest#isEqual}（常量时间），防止按字节提前返回泄露密钥前缀。
 * 错误体与 {@code AgentErrorResponse} 同形：{@code code}/{@code message}/{@code chatId}。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    /** 请求头名（与 {@code RateLimitFilter} 共用，避免两处硬编码漂移）。 */
    static final String API_KEY_HEADER = "X-API-Key";

    private static final String GUARDED_PREFIX = "/ai";

    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"缺少或无效的 API Key\",\"chatId\":null}";

    private final SecurityProperties properties;

    /**
     * 生产装配入口。参数用 {@link ObjectProvider} 而不是 {@code SecurityProperties}：
     * {@code @WebMvcTest} 等窄切片会收集所有 {@code Filter} Bean，却不扫描普通 {@code @Component}，
     * 直接依赖会让切片上下文起不来。取不到配置即视为「未启用鉴权」，见 {@link #doFilterInternal}。
     */
    @Autowired
    public ApiKeyFilter(ObjectProvider<SecurityProperties> propertiesProvider) {
        this(propertiesProvider.getIfAvailable());
    }

    /** 测试用：直接注入配置。 */
    ApiKeyFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (properties == null
                || !requiresApiKey(request)
                || !StringUtils.hasText(properties.getApiKey())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (matches(request.getHeader(API_KEY_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }
        // 只记方法与路径：请求头里的密钥绝不能进日志
        log.debug("拒绝未授权请求：{} {}", request.getMethod(), request.getServletPath());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(JSON_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    /** 常量时间比较；任一侧为 {@code null} 视为不匹配。 */
    private boolean matches(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.getApiKey().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 作用域判定只看 servletPath：{@code /ai} 自身与 {@code /ai/...} 都算，其它路径一律不算。
     * 用 {@code startsWith("/ai/")} 而不是 {@code startsWith("/ai")}，
     * 避免把 {@code /ai-foo} 这类路径误判为受保护资源。
     */
    private static boolean requiresApiKey(HttpServletRequest request) {
        String path = request.getServletPath();
        return path != null && (path.equals(GUARDED_PREFIX) || path.startsWith(GUARDED_PREFIX + "/"));
    }
}
