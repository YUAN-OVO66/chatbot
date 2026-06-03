package com.iflytek.chatbot.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_memory_facts")
@Data
public class UserMemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Column(name = "fact_text", nullable = false, columnDefinition = "TEXT")
    private String factText;

    @Column(name = "category", length = 64)
    private String category = "general";

    @Column(name = "importance", nullable = false)
    private Byte importance = 5;

    @Column(name = "document_id", length = 128)
    private String documentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }


}
