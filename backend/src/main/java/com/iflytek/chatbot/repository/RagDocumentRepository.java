package com.iflytek.chatbot.repository;

import com.iflytek.chatbot.entity.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    List<RagDocument> findByUserIdOrderByCreatedAtDesc(String userId);

    List<RagDocument> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    long countByUserIdAndStatus(String userId, String status);
}
