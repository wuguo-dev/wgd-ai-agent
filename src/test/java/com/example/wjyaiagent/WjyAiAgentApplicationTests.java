package com.example.wjyaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "knowledge-base.rag.enabled=false")
class WjyAiAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
