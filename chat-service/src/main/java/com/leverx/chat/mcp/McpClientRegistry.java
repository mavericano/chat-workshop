package com.leverx.chat.mcp;

import com.leverx.chat.mcp.McpServerDtos.ConnectionStatus;
import com.leverx.chat.mcp.McpServerDtos.McpToolInfo;
import com.leverx.chat.mcp.McpUrlValidator.McpTarget;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Keeps one open MCP client per user-configured server, created at runtime from the database.
 * The tools of a server are listed once on connect and reused for every chat request.
 */
@Component
public class McpClientRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final Pattern STATUS_CODE = Pattern.compile("Status code:\\s*(\\d{3})");

    private final McpProperties properties;
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, String> errors = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public McpClientRegistry(McpProperties properties) {
        this.properties = properties;
    }

    /**
     * Everything needed to open a connection; the header value is already decrypted.
     */
    public record ConnectionSpec(String name, McpTarget target, McpTransportType transport,
                                 String headerName, String headerValue) {
    }

    public record Probe(String serverInfo, List<McpToolInfo> tools) {
    }

    private record Connection(McpSyncClient client, String serverInfo, List<McpToolInfo> tools,
                              List<ToolCallback> callbacks, Instant[] lastUsed) {
    }

    /**
     * Connects, lists the tools and disconnects again; nothing is kept.
     */
    public Probe probe(ConnectionSpec spec) {
        var connection = open(spec);
        connection.client().closeGracefully();
        return new Probe(connection.serverInfo(), connection.tools());
    }

    /**
     * Returns the tools of the server, connecting first if needed. Failures are remembered and reported as status.
     */
    public List<ToolCallback> toolsOf(Long serverId, ConnectionSpec spec) {
        var existing = connections.get(serverId);
        if (existing != null) {
            existing.lastUsed()[0] = Instant.now();
            return existing.callbacks();
        }
        synchronized (locks.computeIfAbsent(serverId, id -> new Object())) {
            existing = connections.get(serverId);
            if (existing != null) {
                return existing.callbacks();
            }
            try {
                var connection = open(spec);
                connections.put(serverId, connection);
                errors.remove(serverId);
                return connection.callbacks();
            } catch (RuntimeException e) {
                errors.put(serverId, describe(e));
                log.warn("Cannot connect to MCP server '{}' ({}): {}", spec.name(), spec.target().baseUrl(), describe(e));
                return List.of();
            }
        }
    }

    public void disconnect(Long serverId) {
        errors.remove(serverId);
        var connection = connections.remove(serverId);
        if (connection != null) {
            log.info("Disconnected MCP server {} ({})", serverId, connection.serverInfo());
            close(connection);
        }
    }

    public ConnectionStatus status(Long serverId, boolean enabled) {
        if (!enabled) {
            return ConnectionStatus.DISABLED;
        }
        if (connections.containsKey(serverId)) {
            return ConnectionStatus.CONNECTED;
        }
        return errors.containsKey(serverId) ? ConnectionStatus.ERROR : ConnectionStatus.DISCONNECTED;
    }

    public String error(Long serverId) {
        return errors.get(serverId);
    }

    public String serverInfo(Long serverId) {
        var connection = connections.get(serverId);
        return connection == null ? null : connection.serverInfo();
    }

    public List<McpToolInfo> tools(Long serverId) {
        var connection = connections.get(serverId);
        return connection == null ? List.of() : connection.tools();
    }

    @Scheduled(fixedDelayString = "PT1M")
    void closeIdleConnections() {
        var threshold = Instant.now().minus(properties.idleTimeout());
        connections.forEach((id, connection) -> {
            if (connection.lastUsed()[0].isBefore(threshold) && connections.remove(id, connection)) {
                log.info("Closing idle MCP connection {} ({})", id, connection.serverInfo());
                close(connection);
            }
        });
    }

    @Override
    public void destroy() {
        connections.values().forEach(McpClientRegistry::close);
        connections.clear();
    }

    private Connection open(ConnectionSpec spec) {
        var startNanos = System.nanoTime();
        var client = McpClient.sync(transport(spec))
                .clientInfo(new McpSchema.Implementation("room-booking-chat", "1.0.0"))
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.connectTimeout())
                .build();
        try {
            var initialized = client.initialize();
            var tools = client.listTools().tools().stream()
                    .map(tool -> new McpToolInfo(tool.name(), tool.description()))
                    .toList();
            var callbacks = Arrays.stream(SyncMcpToolCallbackProvider.builder()
                            .mcpClients(client)
                            .toolNamePrefixGenerator((info, tool) -> toolName(spec.name(), tool.name()))
                            .build()
                            .getToolCallbacks())
                    .<ToolCallback>map(callback -> new LoggingToolCallback(callback, spec.name(),
                            callback.getToolDefinition().name()))
                    .toList();
            var server = initialized.serverInfo();
            var serverInfo = server == null ? null : server.name() + " " + server.version();
            log.info("Connected to MCP server '{}' ({}, {}) in {} ms: {} tools {}", spec.name(), serverInfo,
                    spec.target().baseUrl(), (System.nanoTime() - startNanos) / 1_000_000, tools.size(),
                    tools.stream().map(McpToolInfo::name).toList());
            return new Connection(client, serverInfo, tools, callbacks, new Instant[]{Instant.now()});
        } catch (RuntimeException e) {
            client.closeGracefully();
            throw e;
        }
    }

    private McpClientTransport transport(ConnectionSpec spec) {
        McpSyncHttpClientRequestCustomizer headers = (request, method, uri, body, context) -> {
            if (spec.headerName() != null && spec.headerValue() != null) {
                request.header(spec.headerName(), spec.headerValue());
            }
        };
        return switch (spec.transport()) {
            case STREAMABLE_HTTP -> HttpClientStreamableHttpTransport.builder(spec.target().baseUrl())
                    .endpoint(spec.target().endpoint())
                    .connectTimeout(properties.connectTimeout())
                    .httpRequestCustomizer(headers)
                    .build();
            case SSE -> HttpClientSseClientTransport.builder(spec.target().baseUrl())
                    .sseEndpoint(spec.target().endpoint())
                    .connectTimeout(properties.connectTimeout())
                    .httpRequestCustomizer(headers)
                    .build();
        };
    }

    /**
     * Tool names the model sees: "server__tool", restricted to characters every model provider accepts.
     */
    static String toolName(String serverName, String toolName) {
        var name = sanitize(serverName).toLowerCase(Locale.ROOT) + "__" + sanitize(toolName);
        return name.length() <= MAX_TOOL_NAME_LENGTH ? name : name.substring(0, MAX_TOOL_NAME_LENGTH);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * Turns MCP SDK failures into a short message the user can act on.
     */
    static String describe(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpHttpClientTransportAuthorizationException) {
                return "Authorization failed: check the auth header and its value";
            }
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                return "The server did not respond in time";
            }
            if (cause instanceof ConnectException || cause instanceof UnresolvedAddressException) {
                return "Cannot connect to the server";
            }
            var message = cause.getMessage() == null ? "" : cause.getMessage();
            var status = STATUS_CODE.matcher(message);
            if (status.find()) {
                return status.group(1).equals("404")
                        ? "No MCP endpoint at this URL (HTTP 404): check the path"
                        : "The server responded with HTTP " + status.group(1);
            }
            if (message.contains("DummyEvent")) {
                return "The server did not respond like an MCP server: check the URL and the transport";
            }
        }
        var message = NestedExceptionUtils.getMostSpecificCause(error).getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.lines().findFirst().orElse(error.getClass().getSimpleName());
    }

    private static void close(Connection connection) {
        try {
            connection.client().closeGracefully();
        } catch (RuntimeException e) {
            log.debug("Error while closing MCP client", e);
        }
    }
}
