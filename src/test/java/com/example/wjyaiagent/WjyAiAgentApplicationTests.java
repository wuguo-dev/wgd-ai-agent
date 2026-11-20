package com.example.wjyaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"knowledge-base.rag.enabled=false", "spring.flyway.enabled=false"})
class WjyAiAgentApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.wjyaiagent.agent.repository.AgentRepository agentRepository;

    @Test
    void contextLoads() {
    }

}
