package com.leverx.chat.mcp;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientRegistryTest {

    @Test
    void prefixesToolNamesWithServerNameUsingSafeCharacters() {
        assertThat(McpClientRegistry.toolName("GitHub", "search_code")).isEqualTo("github__search_code");
        assertThat(McpClientRegistry.toolName("my-server", "get.weather")).isEqualTo("my_server__get_weather");
    }

    @Test
    void limitsToolNameLength() {
        assertThat(McpClientRegistry.toolName("server", "x".repeat(100))).hasSize(64);
    }

    @Test
    void describesConnectionFailuresForUsers() {
        assertThat(McpClientRegistry.describe(new RuntimeException("Client failed to initialize",
                new McpTransportException("Server Not Found. Status code:404, response-event:DummyEvent[x]"))))
                .contains("HTTP 404");
        assertThat(McpClientRegistry.describe(new RuntimeException("Client failed to initialize",
                new McpHttpClientTransportAuthorizationException("Authorization error", null, null))))
                .contains("Authorization failed");
        assertThat(McpClientRegistry.describe(new RuntimeException("Client failed to initialize",
                new RuntimeException("Failed to send message: DummyEvent[responseInfo=x]"))))
                .contains("did not respond like an MCP server");
        assertThat(McpClientRegistry.describe(new RuntimeException(new java.util.concurrent.TimeoutException())))
                .contains("in time");
    }

    @Test
    void masksSecrets() {
        assertThat(McpServerService.mask("Bearer ghp_1234567890abcdef")).isEqualTo("Bear…cdef");
        assertThat(McpServerService.mask("short")).isEqualTo("••••");
    }
}
