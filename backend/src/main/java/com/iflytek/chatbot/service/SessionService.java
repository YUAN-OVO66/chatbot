package com.iflytek.chatbot.service;

import com.iflytek.chatbot.dto.SessionResponse;
import com.iflytek.chatbot.entity.ChatSession;
import com.iflytek.chatbot.repository.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final ChatSessionRepository sessionRepository;

    public SessionService(ChatSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public ChatSession createSession(String userId) {
        String id = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(id, userId);
        session.setTitle("New Chat");
        ChatSession saved = sessionRepository.save(session);
        log.info("[Session] 新建会话 | sessionId={}, userId={}", id, userId);
        return saved;
    }

    public ChatSession getSession(String sessionId) {
        log.debug("[Session] 查询会话 | sessionId={}", sessionId);
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> {
                    log.warn("[Session] 会话不存在 | sessionId={}", sessionId);
                    return new RuntimeException("Session not found: " + sessionId);
                });
    }

    public Page<SessionResponse> listUserSessions(String userId, Pageable pageable) {
        return sessionRepository.findByUserIdAndIsActiveOrderByUpdatedAtDesc(userId, true, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public void archiveSession(String sessionId) {
        ChatSession session = getSession(sessionId);
        session.setIsActive(false);
        sessionRepository.save(session);
        log.info("[Session] 归档会话 | sessionId={}", sessionId);
    }

    @Transactional
    public void updateSessionTitle(String sessionId, String title) {
        ChatSession session = getSession(sessionId);
        session.setTitle(title);
        sessionRepository.save(session);
    }

    @Transactional
    public void updateSessionSummary(String sessionId, String summary) {
        ChatSession session = getSession(sessionId);
        session.setSummary(summary);
        sessionRepository.save(session);
    }

    private SessionResponse toResponse(ChatSession session) {
        return new SessionResponse(
                session.getId(),
                session.getUserId(),
                session.getTitle(),
                session.getSummary(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getIsActive()
        );
    }
}
