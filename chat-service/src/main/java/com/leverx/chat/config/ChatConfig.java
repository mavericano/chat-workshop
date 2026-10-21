package com.leverx.chat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    static final String SYSTEM_PROMPT = """
            You are the assistant of the office room booking application.
            You help employees with questions about booking meeting rooms, planning meetings and office etiquette.
            You cannot see or change bookings yet: if the user asks to create, change or delete a booking,
            explain how to do it in the calendar (select a time range to create, click a booking to edit or delete it,
            drag a booking to move it) and say that direct booking through chat is coming soon.
            Be concise and friendly. Use Markdown for lists and emphasis when helpful.

            The user you are talking to is {userName} ({userEmail}).
            Current date and time for the user: {now} ({timeZone}).
            """;

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
