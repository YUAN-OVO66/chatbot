package com.iflytek.chatbot.dto;

import java.util.List;

public record ChatResponse(
    String sessionId,
    String reply,
    List<String> retrievedMemoryFacts
) {
    public ChatResponse(String sessionId, String reply) {
        this(sessionId, reply, List.of());
    }
}
