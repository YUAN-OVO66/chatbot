package com.iflytek.chatbot.service;

import com.iflytek.chatbot.util.IdValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 语义记忆服务：管理 Milvus 向量库中的对话和事实文档
 */
@Service
public class SemanticMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryService.class);

    private final VectorStore vectorStore;

    public SemanticMemoryService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 存储对话片段到 Milvus
     */
    public void storeConversationChunk(String userId, String conversationId,
                                        String userMessage, String assistantReply) {
        IdValidators.requireSafeId(userId, "userId");
        IdValidators.requireSafeId(conversationId, "conversationId");
        String content = "[User]: " + userMessage + "\n[Assistant]: " + assistantReply;
        log.debug("[Milvus] storeConversation | userId={}, conversationId={}, len={}",
                userId, conversationId, content.length());

        Document doc = new Document(content, Map.of(
                "userId", userId,
                "conversationId", conversationId,
                "type", "conversation",
                "timestamp", LocalDateTime.now().toString()
        ));
        vectorStore.add(List.of(doc));
    }

    /**
     * 语义检索相似历史对话
     */
    public List<Document> searchRelevantConversations(String userId, String query, int topK) {
        IdValidators.requireSafeId(userId, "userId");
        log.debug("[Milvus] searchConversations | userId={}, topK={}", userId, topK);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("userId == '" + userId + "' && type == 'conversation'")
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        log.debug("[Milvus] searchConversations 返回 {} 条 | userId={}", results.size(), userId);
        return results;
    }

    /**
     * 存储提取的事实到 Milvus
     */
    public void storeFactDocument(String userId, String factText, String category,
                                   byte importance, Long factId) {
        IdValidators.requireSafeId(userId, "userId");
        log.debug("[Milvus] storeFact | userId={}, factId={}, category={}", userId, factId, category);

        Document doc = new Document(factText, Map.of(
                "userId", userId,
                "type", "fact",
                "category", category,
                "importance", String.valueOf(importance),
                "factId", String.valueOf(factId)
        ));
        vectorStore.add(List.of(doc));
    }

    /**
     * 语义检索相关事实
     */
    public List<Document> searchRelevantFacts(String userId, String query, int topK) {
        IdValidators.requireSafeId(userId, "userId");
        log.debug("[Milvus] searchFacts | userId={}, topK={}", userId, topK);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("userId == '" + userId + "' && type == 'fact'")
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        log.debug("[Milvus] searchFacts 返回 {} 条 | userId={}", results.size(), userId);
        return results;
    }

    /**
     * 删除指定会话的所有向量文档
     */
    public void deleteConversationDocuments(String conversationId) {
        IdValidators.requireSafeId(conversationId, "conversationId");
        log.debug("[Milvus] deleteConversation | conversationId={}", conversationId);
        vectorStore.delete("conversationId == '" + conversationId + "'");
    }

    public void deleteFactDocument(Long factId) {
        IdValidators.requireSafeNumericId(String.valueOf(factId), "factId");
        log.debug("[Milvus] deleteFact | factId={}", factId);
        vectorStore.delete("factId == '" + factId + "'");
    }
}
