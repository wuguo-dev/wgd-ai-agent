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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 令牌桶限流：作用域同 {@link ApiKeyFilter}，只保护 {@code /ai/**}。
 *
 * <p>桶按「凭据」隔离：配置了 API Key 时用请求头 {@code X-API-Key} 作为键，
 * 未配置时（本地开发）退化为按 {@code getRemoteAddr()} 计数。令牌桶容量取
 * {@code rate-limit-burst}，补充速率取 {@code rate-limit-per-minute / 60}。</p>
 *
 * <p>桶表随 key 增长，因此在元素超过 10000 个时做一次「已满且 5 分钟未访问」的回收：
 * 已满说明该 key 很久没被限流，闲置说明它已不活跃，回收后不会丢失任何限流额度。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    /** 桶表上限：超过后触发一次垃圾回收，避免匿名客户端把内存打爆。 */
    static final int MAX_BUCKETS = 10_000;

    /** 桶的闲置判定阈值：只有「满 + 闲置」的桶才可回收。 */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);

    private static final String GUARDED_PREFIX = "/ai";

    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private static final String RATE_LIMITED_BODY =
            "{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后重试\",\"chatId\":null}";

    private final SecurityProperties properties;

    /** 单调纳秒时钟；生产用 {@link System#nanoTime()}，测试注入假时钟。 */
    private final LongSupplier nanoTime;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * 生产装配入口：见 {@link ApiKeyFilter} 的同名说明——窄切片里没有配置 Bean 时
     * {@code getIfAvailable()} 返回 {@code null}，此时不限流，保证切片上下文可启动。
     */
    @Autowired
    public RateLimitFilter(ObjectProvider<SecurityProperties> propertiesProvider) {
        this(propertiesProvider.getIfAvailable(), System::nanoTime);
    }

    /** 测试用：注入配置与假时钟。 */
    RateLimitFilter(SecurityProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (properties == null || !requiresRateLimit(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (buckets.size() > MAX_BUCKETS) {
            evictIdleBuckets();
        }
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey(request), key -> newBucket());
        if (bucket.tryAcquire()) {
            filterChain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1L, bucket.retryAfterSeconds());
        log.debug("拒绝超限请求：{} {}，{} 秒后重试",
                request.getMethod(), request.getServletPath(), retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
        response.setContentType(JSON_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(RATE_LIMITED_BODY);
    }

    /**
     * 桶键：配了 API Key 就用该请求的 {@code X-API-Key}，否则用客户端地址。
     *
     * <p>配置了 key 但请求没带（理论上早已被 {@link ApiKeyFilter} 拦掉）时回落到地址，
     * 既避免 {@link ConcurrentHashMap} 拒绝 null 键，也保证限流不会因此失效。</p>
     */
    private String bucketKey(HttpServletRequest request) {
        if (StringUtils.hasText(properties.getApiKey())) {
            String provided = request.getHeader(ApiKeyFilter.API_KEY_HEADER);
            if (StringUtils.hasText(provided)) {
                return provided;
            }
        }
        return "ip:" + request.getRemoteAddr();
    }

    private TokenBucket newBucket() {
        return new TokenBucket(properties.getRateLimitBurst(),
                properties.getRateLimitPerMinute() / 60.0d, nanoTime);
    }

    /** 只回收「已满且闲置超过 5 分钟」的桶；用 remove(key, value) 保证不误删刚被替换的桶。 */
    private void evictIdleBuckets() {
        int removed = 0;
        for (Map.Entry<String, TokenBucket> entry : buckets.entrySet()) {
            TokenBucket bucket = entry.getValue();
            if (bucket.isFull() && bucket.idleFor(IDLE_TIMEOUT) && buckets.remove(entry.getKey(), bucket)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("已回收 {} 个闲置令牌桶，剩余 {} 个", removed, buckets.size());
        }
    }

    /** 作用域判定与 {@link ApiKeyFilter} 保持一致：{@code /ai} 与 {@code /ai/...}。 */
    private static boolean requiresRateLimit(HttpServletRequest request) {
        String path = request.getServletPath();
        return path != null && (path.equals(GUARDED_PREFIX) || path.startsWith(GUARDED_PREFIX + "/"));
    }
}
