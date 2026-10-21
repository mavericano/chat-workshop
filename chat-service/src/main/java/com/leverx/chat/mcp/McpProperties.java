package com.leverx.chat.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Limits and security settings for MCP servers that users add from the chat UI.
 */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(
        int maxServersPerUser,
        int maxToolsPerRequest,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration idleTimeout,
        List<String> allowedHosts,
        Secrets secrets) {

    public McpProperties {
        allowedHosts = allowedHosts == null ? List.of()
                : allowedHosts.stream().map(String::strip).filter(h -> !h.isEmpty()).toList();
    }

    public record Secrets(String password, String salt) {
    }
}
