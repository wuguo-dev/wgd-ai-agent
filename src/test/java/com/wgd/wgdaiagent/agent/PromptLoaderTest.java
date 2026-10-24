package com.wgd.wgdaiagent.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11（规格 §9.3）：system prompt 资产必须可读且非空，缺失即启动失败（不做降级）。
 */
@Tag("offline")
class PromptLoaderTest {

    @Test
    void loadsPromptAssetFromClasspath() {
        String text = new PromptLoader().text();

        assertFalse(text.isBlank(), "prompt 资产不应为空白");
        assertTrue(text.contains("恋爱"), "应是恋爱顾问的系统提示词");
    }

    @Test
    void missingAssetFailsFast() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PromptLoader("classpath:prompts/does-not-exist.txt"));

        assertTrue(error.getMessage().contains("prompt 资产缺失"), error.getMessage());
    }

    @Test
    void blankAssetFailsFast(@TempDir Path tempDir) throws Exception {
        Path blank = Files.createFile(tempDir.resolve("blank-prompt.txt"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PromptLoader(blank.toUri().toString()));

        assertTrue(error.getMessage().contains("空白"), error.getMessage());
    }
}
