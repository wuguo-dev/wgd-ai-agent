package com.wgd.wgdaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T29（规格 §9.3 / AC3）：真模型 + 真高德的冒烟用例。
 *
 * <p><b>只有 {@code live} tag，默认门槛（{@code mvn -o test}）不执行</b>；
 * 需要 {@code AMAP_MCP_KEY}、DashScope key、RDS 等真实环境，会花钱：
 * {@code mvn -Plive test -Dtest=LoveAgentLiveTest}。</p>
 */
@Tag("live")
@SpringBootTest
class LoveAgentLiveTest {

    @Resource
    private LoveApp loveApp;

    @Test
    void agentAnswersLocationQuestionWithToolCall() {
        String chatId = "live-agent-" + System.currentTimeMillis();

        String answer = loveApp.doChatWithAgent("北京朝阳区适合约会的咖啡馆推荐一个", chatId);

        assertNotNull(answer, "agent 必须给出答复");
        assertFalse(answer.isBlank(), "答复不能为空（地点类问题应触发高德 MCP 工具）");
    }

    @Test
    void agentAnswersTimeQuestionWithCurrentYear() {
        String chatId = "live-agent-time-" + System.currentTimeMillis();

        String answer = loveApp.doChatWithAgent("现在几点了？", chatId);

        assertNotNull(answer, "agent 必须给出答复");
        // 时间类问题应触发本地 currentTime 工具：答复里出现当前年份，说明时间来自工具而非模型自行推算
        int currentYear = LocalDate.now().getYear();
        assertTrue(answer.contains(String.valueOf(currentYear)),
                "答复应包含当前年份 " + currentYear + "，实际：" + answer);
    }
}
