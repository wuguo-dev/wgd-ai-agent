package com.example.wjyaiagent.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "knowledge-base.security")
public class SecurityProperties {
    private String apiKey = "";
    private int rateLimitPerMinute = 30;
    private int rateLimitBurst = 30;
}
