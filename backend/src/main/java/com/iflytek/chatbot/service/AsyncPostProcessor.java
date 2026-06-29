package com.iflytek.chatbot.service;

import com.iflytek.chatbot.entity.MemoryExtractionLog;
import com.iflytek.chatbot.repository.MemoryExtractionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AsyncPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncPostProcessor.class);
    private static final int EXTRACT_INTERVAL_MESSAGES = 4;

    private final ChatMemory chatMemory;
    private final SemanticMemoryService semanticMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryExtractionLogRepository extractionLogRepository;

    public AsyncPostProcessor(ChatMemory chatMemory,
                               SemanticMemoryService semanticMemoryService,
                               LongTermMemoryService longTermMemoryService,
                               MemoryExtractionLogRepository extractionLogRepository) {
        this.chatMemory = chatMemory;
        this.semanticMemoryService = semanticMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.extractionLogRepository = extractionLogRepository;
    }

    @Async("chatTaskExecutor")
    public void extractAndStore(String sessionId, String userId,
                                String userMessage, String assistantReply) {
        try {
            log.debug("[Async] 后处理开始 | sessionId={}, userId={}", sessionId, userId);

            semanticMemoryService.storeConversationChunk(userId, sessionId, userMessage, assistantReply);

            List<Message> messages = chatMemory.get(sessionId);
            int currentSize = messages.size();
            int lastSize = extractionLogRepository
                    .findTopByConversationIdAndStatusOrderByCreatedAtDesc(sessionId, "success")
                    .map(MemoryExtractionLog::getInputMessageCount)
                    .orElse(0);

            if (currentSize >= EXTRACT_INTERVAL_MESSAGES
                    && currentSize - lastSize >= EXTRACT_INTERVAL_MESSAGES) {
                log.info("[Async] 触发事实提取 | sessionId={}, msgCount={}, lastSize={}",
                        sessionId, currentSize, lastSize);
                longTermMemoryService.extractFacts(sessionId, userId, messages);
            } else {
                log.debug("[Async] 跳过事实提取（节流） | sessionId={}, current={}, last={}",
                        sessionId, currentSize, lastSize);
            }
        } catch (Exception e) {
            log.error("[Async] 后处理失败 | sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }
}
