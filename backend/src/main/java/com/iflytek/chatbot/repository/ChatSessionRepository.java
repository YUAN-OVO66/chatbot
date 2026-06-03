package com.iflytek.chatbot.repository;

import com.iflytek.chatbot.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    Page<ChatSession> findByUserIdAndIsActiveOrderByUpdatedAtDesc(String userId, Boolean isActive, Pageable pageable);

    List<ChatSession> findByUserIdAndIsActiveOrderByUpdatedAtDesc(String userId, Boolean isActive);

    long countByUserIdAndIsActive(String userId, Boolean isActive);
}
