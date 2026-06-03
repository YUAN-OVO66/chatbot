package com.iflytek.chatbot.dto;

/**
 * 聊天历史消息 DTO
 */
public record ChatMessage(
    String role,
    String content
) {}
