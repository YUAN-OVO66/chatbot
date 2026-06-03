package com.iflytek.chatbot.dto;

public record ChatRequest(
    String sessionId,
    String userId,
    String message
) {}
