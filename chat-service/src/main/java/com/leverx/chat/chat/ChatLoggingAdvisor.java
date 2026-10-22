package com.leverx.chat.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Logs every round trip to the model. It is ordered right after Spring AI's {@link ToolCallingAdvisor}, so it runs
 * once per model call: a round can end with tool calls, and the next round sends the tool results back to the model.
 * INFO shows sizes, tool calls, timings and token usage; DEBUG also shows the prompt messages themselves (system
 * prompt, history, tool results), which contain personal data and are therefore not logged by default.
 */
public class ChatLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 1;

    private static final Logger log = LoggerFactory.getLogger(ChatLoggingAdvisor.class);
    private static final int MAX_LOGGED_TEXT = 300;

    @Override
    public String getName() {
        return "chat-logging";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var round = Round.start(request);
        try {
            var response = chain.nextCall(request);
            round.add(response);
            round.finished("complete");
            return response;
        } catch (RuntimeException e) {
            round.failed(e);
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            var round = Round.start(request);
            return chain.nextStream(request)
                    .doOnNext(round::add)
                    .doOnError(round::failed)
                    .doOnComplete(() -> round.finished("complete"))
                    .doOnCancel(() -> round.finished("cancelled by client"));
        });
    }

    /**
     * Rounds are counted from the prompt itself: every assistant message with tool calls after the latest user
     * message is a previous round of the same turn.
     */
    static int roundNumber(List<Message> messages) {
        var round = 1;
        for (var i = messages.size() - 1; i >= 0 && messages.get(i).getMessageType() != MessageType.USER; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                round++;
            }
        }
        return round;
    }

    static String shorten(String text) {
        if (text == null) {
            return "";
        }
        var oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_LOGGED_TEXT
                ? oneLine
                : oneLine.substring(0, MAX_LOGGED_TEXT) + "…(" + oneLine.length() + " chars)";
    }

    /**
     * State of one model call; a stream delivers it in many chunks, so the values are accumulated.
     */
    private static final class Round {

        private final Object conversationId;
        private final int number;
        private final long startNanos = System.nanoTime();
        private long firstChunkNanos;
        private int chunks;
        private int answerChars;
        private final List<String> toolCalls = new ArrayList<>();
        private Usage usage;
        private String finishReason;
        private String model;

        private Round(Object conversationId, int number) {
            this.conversationId = conversationId;
            this.number = number;
        }

        static Round start(ChatClientRequest request) {
            var messages = request.prompt().getInstructions();
            var round = new Round(request.context().get(ChatMemory.CONVERSATION_ID), roundNumber(messages));

            Map<MessageType, Integer> counts = new EnumMap<>(MessageType.class);
            var promptChars = 0;
            for (var message : messages) {
                counts.merge(message.getMessageType(), 1, Integer::sum);
                promptChars += textLength(message);
            }
            var options = request.prompt().getOptions();
            var tools = options instanceof ToolCallingChatOptions toolOptions ? toolOptions.getToolCallbacks().size() : 0;
            log.info("Model round {} → conversation={} model={} temperature={} messages={} promptChars={} tools={}",
                    round.number, round.conversationId, options == null ? null : options.getModel(),
                    options == null ? null : options.getTemperature(), counts, promptChars, tools);
            if (log.isDebugEnabled()) {
                messages.forEach(message -> log.debug("  prompt [{}] {}", message.getMessageType(), describe(message)));
            }
            return round;
        }

        void add(ChatClientResponse response) {
            var chatResponse = response.chatResponse();
            if (chatResponse == null) {
                return;
            }
            if (chunks++ == 0) {
                firstChunkNanos = System.nanoTime();
            }
            var metadata = chatResponse.getMetadata();
            if (metadata != null) {
                if (metadata.getUsage() != null && total(metadata.getUsage()) > 0) {
                    // streamed chunks repeat the usage so far; the last one holds the totals
                    usage = metadata.getUsage();
                }
                if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                    model = metadata.getModel();
                }
            }
            for (var generation : chatResponse.getResults()) {
                var output = generation.getOutput();
                if (output.getText() != null) {
                    answerChars += output.getText().length();
                }
                output.getToolCalls().forEach(call -> toolCalls.add(call.name() + " " + shorten(call.arguments())));
                if (generation.getMetadata().getFinishReason() != null) {
                    finishReason = generation.getMetadata().getFinishReason();
                }
            }
        }

        void finished(String outcome) {
            var tokens = usage == null ? "n/a" : "in=%s out=%s total=%s".formatted(
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            if (!toolCalls.isEmpty()) {
                log.info("Model round {} ← {} tool call(s): {} ({} ms, model={}, tokens {}, {})", number,
                        toolCalls.size(), toolCalls, elapsedMs(startNanos), model, tokens, outcome);
            } else {
                log.info("Model round {} ← answer {} chars in {} chunks, finish={} ({} ms, first chunk after {} ms, "
                                + "model={}, tokens {}, {})", number, answerChars, chunks, finishReason,
                        elapsedMs(startNanos), chunks == 0 ? "-" : (firstChunkNanos - startNanos) / 1_000_000, model,
                        tokens, outcome);
            }
        }

        void failed(Throwable error) {
            log.warn("Model round {} failed after {} ms: {}", number, elapsedMs(startNanos), error.toString());
        }

        /**
         * Tool calls and tool results carry their content outside {@link Message#getText()}.
         */
        private static int textLength(Message message) {
            var length = message.getText() == null ? 0 : message.getText().length();
            if (message instanceof AssistantMessage assistant) {
                length += assistant.getToolCalls().stream().mapToInt(call -> call.arguments().length()).sum();
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                length += toolResponse.getResponses().stream().mapToInt(r -> r.responseData().length()).sum();
            }
            return length;
        }

        private static long total(Usage usage) {
            var total = usage.getTotalTokens();
            return total == null ? 0 : total;
        }

        private static long elapsedMs(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }

        private static String describe(Message message) {
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                return "tool calls " + assistant.getToolCalls().stream()
                        .map(call -> call.name() + " " + shorten(call.arguments())).toList();
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                return "tool results " + toolResponse.getResponses().stream()
                        .map(r -> r.name() + " = " + shorten(r.responseData())).toList();
            }
            return shorten(message.getText());
        }
    }
}
