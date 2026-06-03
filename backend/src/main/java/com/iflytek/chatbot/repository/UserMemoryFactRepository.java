package com.iflytek.chatbot.repository;

import com.iflytek.chatbot.entity.UserMemoryFact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserMemoryFactRepository extends JpaRepository<UserMemoryFact, Long> {

    Optional<UserMemoryFact> findByUserIdAndFactTextAndIsActive(String userId, String factText, Boolean isActive);

    List<UserMemoryFact> findByUserIdAndIsActiveOrderByImportanceDescCreatedAtDesc(String userId, Boolean isActive);

    Page<UserMemoryFact> findByUserIdAndIsActiveOrderByImportanceDescCreatedAtDesc(String userId, Boolean isActive, Pageable pageable);

    List<UserMemoryFact> findByUserIdAndCategoryAndIsActiveOrderByImportanceDesc(String userId, String category, Boolean isActive);

    List<UserMemoryFact> findByConversationIdAndIsActive(String conversationId, Boolean isActive);
}
