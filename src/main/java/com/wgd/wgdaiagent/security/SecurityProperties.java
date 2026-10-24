package com.wgd.wgdaiagent.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可选 API Key 鉴权与令牌桶限流的配置绑定（前缀 {@code love-app.security}）。
 *
 * <p>{@code api-key} 默认空串：未配置时鉴权整体关闭、限流退化为按客户端 IP 计数，
 * 这样本地开发与既有离线测试不需要任何凭据。故意不用 Lombok {@code @Data}：
 * 它生成的 {@code toString()} 会把密钥打出去。</p>
 */
@Component
@ConfigurationProperties(prefix = "love-app.security")
@Getter
@Setter
public class SecurityProperties {

    /** 期望的 API Key；为空表示不校验。生产环境经 {@code LOVE_APP_API_KEY} 注入。 */
    private String apiKey = "";

    /** 每秒补充的令牌数 = 该值 / 60（默认每分钟 30 次）。 */
    private int rateLimitPerMinute = 30;

    /** 令牌桶容量，即允许的瞬时突发次数。 */
    private int rateLimitBurst = 30;
}
