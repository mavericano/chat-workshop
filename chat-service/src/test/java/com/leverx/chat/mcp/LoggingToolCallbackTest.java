package com.leverx.chat.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingToolCallbackTest {

    private static ToolCallback tool(java.util.function.Function<String, String> behaviour) {
        var definition = ToolDefinition.builder().name("deepwiki__ask_question").description("d").inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return behaviour.apply(toolInput);
            }
        };
    }

    @Test
    void delegatesCallAndDefinition() {
        var logging = new LoggingToolCallback(tool(input -> "echo " + input), "deepwiki", "deepwiki__ask_question");

        assertThat(logging.call("{\"q\":\"x\"}")).isEqualTo("echo {\"q\":\"x\"}");
        assertThat(logging.getToolDefinition().name()).isEqualTo("deepwiki__ask_question");
    }

    @Test
    void rethrowsToolFailures() {
        var logging = new LoggingToolCallback(tool(input -> {
            throw new IllegalStateException("server down");
        }), "deepwiki", "deepwiki__ask_question");

        assertThatThrownBy(() -> logging.call("{}")).isInstanceOf(IllegalStateException.class).hasMessage("server down");
    }
}
