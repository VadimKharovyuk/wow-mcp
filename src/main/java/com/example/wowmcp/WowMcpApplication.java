package com.example.wowmcp;

import com.example.wowmcp.tools.BotActionTools;
import com.example.wowmcp.tools.GrindTools;
import com.example.wowmcp.tools.InventoryTools;
import com.example.wowmcp.tools.PlaceTools;
import com.example.wowmcp.tools.RecordingTools;
import com.example.wowmcp.tools.RotationTools;
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
    public ToolCallbackProvider wowToolCallbacks(BotActionTools actions, GrindTools grind,
                                                 RecordingTools recording, RotationTools rotation,
                                                 InventoryTools inventory, PlaceTools places) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(actions, grind, recording, rotation, inventory, places)
                .build();
    }

}
