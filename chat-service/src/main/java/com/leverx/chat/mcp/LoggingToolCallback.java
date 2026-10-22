package com.leverx.chat.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Logs every MCP tool call the model makes: which server and tool, the arguments, how long it took and the outcome.
 * Arguments come from the model and may contain what the user typed, so they are shortened, never logged in full.
 */
final class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);
    private static final int MAX_LOGGED_ARGUMENTS = 500;
    private static final int MAX_LOGGED_RESULT = 300;

    private final ToolCallback delegate;
    private final String serverName;
    private final String toolName;

    LoggingToolCallback(ToolCallback delegate, String serverName, String toolName) {
        this.delegate = delegate;
        this.serverName = serverName;
        this.toolName = toolName;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        log.info("MCP tool call: server={} tool={} args={}", serverName, toolName, shorten(toolInput, MAX_LOGGED_ARGUMENTS));
        long start = System.nanoTime();
        try {
            var result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            log.info("MCP tool done: server={} tool={} in {} ms, result {} chars", serverName, toolName,
                    elapsedMs(start), result == null ? 0 : result.length());
            log.debug("MCP tool result: server={} tool={} result={}", serverName, toolName,
                    shorten(result, MAX_LOGGED_RESULT));
            return result;
        } catch (RuntimeException e) {
            log.warn("MCP tool failed: server={} tool={} in {} ms: {}", serverName, toolName, elapsedMs(start),
                    e.getMessage());
            throw e;
        }
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "null";
        }
        var oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…(" + oneLine.length() + " chars)";
    }
}
