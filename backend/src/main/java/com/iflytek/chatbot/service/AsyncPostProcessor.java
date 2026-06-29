package com.iflytek.chatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AsyncPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncPostProcessor.class);

    private final ChatMemory chatMemory;
    private final SemanticMemoryService semanticMemoryService;
    private final LongTermMemoryService longTermMemoryService;

    /** 记录每个 session 上次提取时的消息数，用于节流；容量上限 500，超出时淘汰最久未访问的条目 */
    private final Map<String, Integer> lastExtractedSize = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > 500;
                }
            });

    public AsyncPostProcessor(ChatMemory chatMemory,
                               SemanticMemoryService semanticMemoryService,
                               LongTermMemoryService longTermMemoryService) {
        this.chatMemory = chatMemory;
        this.semanticMemoryService = semanticMemoryService;
        this.longTermMemoryService = longTermMemoryService;
    }

    @Async("chatTaskExecutor")
    public void extractAndStore(String sessionId, String userId,
                                String userMessage, String assistantReply) {
        try {
            log.debug("[Async] 后处理开始 | sessionId={}, userId={}", sessionId, userId);

            semanticMemoryService.storeConversationChunk(userId, sessionId, userMessage, assistantReply);

            List<Message> messages = chatMemory.get(sessionId);
            int currentSize = messages.size();
            int lastSize = lastExtractedSize.getOrDefault(sessionId, 0);

            if (currentSize >= 4 && currentSize - lastSize >= 4) {
                log.info("[Async] 触发事实提取 | sessionId={}, msgCount={}", sessionId, currentSize);
                longTermMemoryService.extractFacts(sessionId, userId, messages);
                lastExtractedSize.put(sessionId, currentSize);
            } else {
                log.debug("[Async] 跳过事实提取（节流） | sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("[Async] 后处理失败 | sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }
}
