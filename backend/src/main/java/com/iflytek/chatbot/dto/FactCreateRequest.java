package com.iflytek.chatbot.dto;

public record FactCreateRequest(
    String userId,
    String factText,
    String category,
    Byte importance
) {}
