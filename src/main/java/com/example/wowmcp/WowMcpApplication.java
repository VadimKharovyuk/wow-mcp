package com.example.wowmcp;

import com.example.wowmcp.tools.WowTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WowMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(WowMcpApplication.class, args);
    }

    /** Регистрирует @Tool-методы как MCP-инструменты. */
    @Bean
    public ToolCallbackProvider wowToolCallbacks(WowTools wowTools) {
        return MethodToolCallbackProvider.builder().toolObjects(wowTools).build();
    }

}
