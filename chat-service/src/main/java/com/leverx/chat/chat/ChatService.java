package com.leverx.chat.chat;

import com.leverx.chat.chat.ChatDtos.ChatMessage;
import com.leverx.chat.mcp.McpServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ChatService {

    static final String API_KEY_PLACEHOLDER = "PUT_YOUR_GEMINI_API_KEY_HERE";

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final McpServerService mcpServerService;
    private final boolean apiKeyConfigured;

    public ChatService(ChatClient chatClient, ChatMemory chatMemory, McpServerService mcpServerService,
                       @Value("${spring.ai.google.genai.api-key}") String apiKey) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.mcpServerService = mcpServerService;
        this.apiKeyConfigured = !apiKey.isBlank() && !API_KEY_PLACEHOLDER.equals(apiKey);
    }

    /**
     * Streams the assistant answer. Each user has a single conversation, keyed by the user id from the token.
     * Tools of the MCP servers the user connected in the chat UI are offered to the model.
     */
    public Flux<String> stream(Jwt user, String message, String timeZone) {
        if (!apiKeyConfigured) {
            throw new ChatUnavailableException(
                    "Gemini API key is not configured. Set GEMINI_API_KEY for chat-service and restart it.");
        }
        var zone = resolveZone(timeZone);
        var conversationId = conversationId(user);
        var tools = mcpServerService.toolCallbacksFor(Long.valueOf(user.getSubject()));
        log.info("Chat turn started: conversation={} messageChars={} timeZone={} mcpTools={}",
                conversationId, message.length(), zone.getId(), tools.size());
        log.debug("Chat turn tools: conversation={} tools={}", conversationId,
                tools.stream().map(tool -> tool.getToolDefinition().name()).toList());
        var turn = new TurnStats();
        return chatClient.prompt()
                .system(s -> s
                        .param("userName", String.valueOf(user.getClaimAsString("name")))
                        .param("userEmail", String.valueOf(user.getClaimAsString("email")))
                        .param("now", ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm")))
                        .param("timeZone", zone.getId()))
                .user(message)
                .toolCallbacks(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .doOnNext(turn::chunk)
                .doFinally(signal -> turn.finished(conversationId, signal));
    }

    public List<ChatMessage> history(Jwt user) {
        return chatMemory.get(conversationId(user)).stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> new ChatMessage(m.getMessageType() == MessageType.USER ? "user" : "assistant", m.getText()))
                .toList();
    }

    public void clear(Jwt user) {
        chatMemory.clear(conversationId(user));
        log.info("Chat history cleared: conversation={}", conversationId(user));
    }

    private static String conversationId(Jwt user) {
        return "user-" + user.getSubject();
    }

    /**
     * What the user experienced in one turn: total time, time until the first words appeared and the answer size.
     * The model rounds and tool calls inside the turn are logged by {@link ChatLoggingAdvisor}.
     */
    private static final class TurnStats {

        private final long startNanos = System.nanoTime();
        private long firstChunkNanos;
        private int chunks;
        private int answerChars;

        void chunk(String text) {
            if (chunks++ == 0) {
                firstChunkNanos = System.nanoTime();
            }
            answerChars += text.length();
        }

        void finished(String conversationId, SignalType signal) {
            var outcome = switch (signal) {
                case ON_COMPLETE -> "complete";
                case CANCEL -> "cancelled by client";
                case ON_ERROR -> "failed";
                default -> signal.toString();
            };
            log.info("Chat turn finished: conversation={} {} in {} ms, first chunk after {} ms, {} chunks, answer {} chars",
                    conversationId, outcome, (System.nanoTime() - startNanos) / 1_000_000,
                    chunks == 0 ? "-" : (firstChunkNanos - startNanos) / 1_000_000, chunks, answerChars);
        }
    }

    private static ZoneId resolveZone(String timeZone) {
        try {
            return timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
