package com.iflytek.chatbot.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "memory_extraction_log")
@Data
public class MemoryExtractionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "extraction_type", nullable = false, length = 32)
    private String extractionType;

    @Column(name = "input_message_count")
    private Integer inputMessageCount; 

    @Column(name = "extracted_count")
    private Integer extractedCount;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "success";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }


}
