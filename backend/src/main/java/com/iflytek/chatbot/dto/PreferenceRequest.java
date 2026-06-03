package com.iflytek.chatbot.dto;

public record PreferenceRequest(
    String userId,
    String preferenceKey,
    String preferenceValue
) {}
