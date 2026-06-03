package com.iflytek.chatbot.repository;

import com.iflytek.chatbot.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    List<UserPreference> findByUserIdOrderByConfidenceDesc(String userId);

    Optional<UserPreference> findByUserIdAndPreferenceKey(String userId, String preferenceKey);

    void deleteByUserIdAndPreferenceKey(String userId, String preferenceKey);
}
