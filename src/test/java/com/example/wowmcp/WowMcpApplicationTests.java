package com.example.wowmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        // В тесте не поднимаем stdio-транспорт: он читает System.in и мешает сборке.
        "spring.ai.mcp.server.enabled=false"
})
class WowMcpApplicationTests {

    @Test
    void contextLoads() {
    }

}
