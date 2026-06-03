package com.iflytek.chatbot.dto;

import java.time.LocalDateTime;

public record SessionResponse(
    String id,
    String userId,
    String title,
    String summary,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Boolean isActive
) {}
