package com.iflytek.chatbot.dto;

import java.time.LocalDateTime;

public record MemoryFactResponse(
    Long id,
    String userId,
    String factText,
    String category,
    Byte importance,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
