package com.iflytek.chatbot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PreferenceResponse(
    Long id,
    String userId,
    String preferenceKey,
    String preferenceValue,
    BigDecimal confidence,
    String source,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
