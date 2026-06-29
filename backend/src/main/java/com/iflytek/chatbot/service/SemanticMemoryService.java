package com.iflytek.chatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 语义记忆服务：管理 Milvus 向量库中的对话和事实文档
 */
@Service
public class SemanticMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryService.class);

    // userId / conversationId: UUID 格式 或 字母数字下划线横线（最长128字符）
    private static final Pattern SAFE_ID = Pattern.compile("^[\\w\\-]{1,128}$");
    // factId: 纯数字
    private static final Pattern SAFE_FACT_ID = Pattern.compile("^\\d{1,19}$");

    private final VectorStore vectorStore;

    public SemanticMemoryService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    private static void requireSafeId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的 " + fieldName + " 格式: " + value);
        }
    }

    private static void requireSafeFactId(String value) {
        if (value == null || !SAFE_FACT_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的 factId 格式: " + value);
        }
    }

    /**
     * 存储对话片段到 Milvus
     */
    public void storeConversationChunk(String userId, String conversationId,
                                        String userMessage, String assistantReply) {
        requireSafeId(userId, "userId");
        requireSafeId(conversationId, "conversationId");
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
        requireSafeId(userId, "userId");
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
        requireSafeId(userId, "userId");
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
        requireSafeId(userId, "userId");
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
        requireSafeId(conversationId, "conversationId");
        log.debug("[Milvus] deleteConversation | conversationId={}", conversationId);
        vectorStore.delete("conversationId == '" + conversationId + "'");
    }

    public void deleteFactDocument(Long factId) {
        requireSafeFactId(String.valueOf(factId));
        log.debug("[Milvus] deleteFact | factId={}", factId);
        vectorStore.delete("factId == '" + factId + "'");
    }
}
