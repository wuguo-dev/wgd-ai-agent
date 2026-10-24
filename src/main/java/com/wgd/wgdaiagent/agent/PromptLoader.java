package com.wgd.wgdaiagent.agent;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 系统提示词加载器（规格 §4.2 / §5.4）。
 *
 * <p>从 classpath 读 UTF-8 文本，<b>不做模板渲染</b>（D14：走
 * {@code ReactAgent.builder().systemPrompt(...)}，正文里的 <code>{}</code> 不会被当占位符）。
 * 资产缺失或空白属于配置错误，启动期直接失败，不做运行时降级。</p>
 */
@Component
public class PromptLoader {

    /** 唯一 system prompt 源。 */
    static final String DEFAULT_LOCATION = "classpath:prompts/love-agent-system.txt";

    private final String text;

    public PromptLoader() {
        this(DEFAULT_LOCATION);
    }

    /** 供离线用例指向不存在的资产（同包可见）。 */
    PromptLoader(String location) {
        this.text = read(location);
    }

    /** 提示词正文（已确认非空白）。 */
    public String text() {
        return text;
    }

    private static String read(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("prompt 资产缺失: " + location);
        }
        String content;
        try (InputStream input = resource.getInputStream()) {
            content = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompt 资产读取失败: " + location, e);
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("prompt 资产为空白: " + location);
        }
        return content;
    }
}
