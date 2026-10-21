package com.leverx.chat.mcp;

public enum McpTransportType {

    /** Current MCP HTTP transport (single endpoint, usually /mcp). */
    STREAMABLE_HTTP("/mcp"),

    /** Legacy HTTP + server-sent events transport (usually /sse). */
    SSE("/sse");

    private final String defaultEndpoint;

    McpTransportType(String defaultEndpoint) {
        this.defaultEndpoint = defaultEndpoint;
    }

    public String defaultEndpoint() {
        return defaultEndpoint;
    }
}
