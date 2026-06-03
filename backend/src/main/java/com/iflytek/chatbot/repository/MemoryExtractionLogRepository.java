package com.iflytek.chatbot.repository;

import com.iflytek.chatbot.entity.MemoryExtractionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MemoryExtractionLogRepository extends JpaRepository<MemoryExtractionLog, Long> {

    List<MemoryExtractionLog> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    List<MemoryExtractionLog> findByUserIdOrderByCreatedAtDesc(String userId);
}
