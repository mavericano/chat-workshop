package com.leverx.chat.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLoggingAdvisorTest {

    private static AssistantMessage toolCall() {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "deepwiki__ask_question", "{}")))
                .build();
    }

    private static ToolResponseMessage toolResult() {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("1", "deepwiki__ask_question", "answer")))
                .build();
    }

    @Test
    void firstRoundOfATurn() {
        var messages = List.<Message>of(new SystemMessage("system"), new UserMessage("hi"), new AssistantMessage("hello"),
                new UserMessage("what is spring ai?"));

        assertThat(ChatLoggingAdvisor.roundNumber(messages)).isEqualTo(1);
    }

    @Test
    void countsToolRoundsAfterTheLatestUserMessageOnly() {
        var messages = List.<Message>of(new UserMessage("earlier"), toolCall(), toolResult(), new AssistantMessage("old answer"),
                new UserMessage("now"), toolCall(), toolResult(), toolCall(), toolResult());

        assertThat(ChatLoggingAdvisor.roundNumber(messages)).isEqualTo(3);
    }

    @Test
    void shortensLongTextToOneLine() {
        assertThat(ChatLoggingAdvisor.shorten("a\n  b")).isEqualTo("a b");
        assertThat(ChatLoggingAdvisor.shorten("x".repeat(400))).startsWith("x".repeat(300)).endsWith("…(400 chars)");
        assertThat(ChatLoggingAdvisor.shorten(null)).isEmpty();
    }
}
