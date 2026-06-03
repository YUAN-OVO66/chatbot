package com.iflytek.chatbot.dto;

import java.time.LocalDateTime;

public record RagDocumentResponse(
        Long id,
        String userId,
        String fileName,
        String fileType,
        Long fileSize,
        String status,
        Integer chunkCount,
        LocalDateTime createdAt
) {}
