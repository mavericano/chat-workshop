package com.leverx.chat.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class McpServerDtos {

    private McpServerDtos() {
    }

    /**
     * @param name        short identifier, also used as prefix of the tool names the model sees
     * @param headerName  optional auth header sent with every request, e.g. "Authorization"
     * @param headerValue its value, e.g. "Bearer ..."; stored encrypted and never returned
     */
    public record McpServerRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}",
                    message = "must be 1-40 characters: letters, digits, '-' or '_'") String name,
            @NotBlank @Size(max = 2000) String url,
            @NotNull McpTransportType transport,
            @Pattern(regexp = "[A-Za-z0-9-]{1,100}", message = "must be a valid HTTP header name") String headerName,
            @Size(max = 2000) @Pattern(regexp = "[^\\r\\n]*", message = "must be a single line") String headerValue,
            Boolean enabled) {
    }

    public record McpServerPatch(@NotNull Boolean enabled) {
    }

    public record McpToolInfo(String name, String description) {
    }

    public enum ConnectionStatus { CONNECTED, ERROR, DISCONNECTED, DISABLED }

    public record McpServerResponse(
            Long id,
            String name,
            String url,
            McpTransportType transport,
            boolean enabled,
            String headerName,
            String headerValueMasked,
            ConnectionStatus status,
            String error,
            String serverInfo,
            List<McpToolInfo> tools,
            Instant createdAt) {
    }

    public record McpTestResult(boolean success, String serverInfo, List<McpToolInfo> tools, String error) {
    }
}
