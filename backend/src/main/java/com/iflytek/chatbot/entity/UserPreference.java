package com.iflytek.chatbot.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "user_preferences",
       uniqueConstraints = @UniqueConstraint(name = "idx_user_pref_key", columnNames = {"user_id", "preference_key"}))
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "preference_key", nullable = false, length = 128)
    private String preferenceKey;

    @Column(name = "preference_value", nullable = false, columnDefinition = "TEXT")
    private String preferenceValue;

    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence = new BigDecimal("0.50");

    @Column(name = "source", length = 64)
    private String source = "extracted";

    @Column(name = "document_id", length = 128)
    private String documentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
