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
        String content = "[User]: " + userMessage + "\n[Assistant]: " + assistantReply;
        log.info("[Milvus] 存储对话片段 | userId={}, conversationId={}, 内容长度={}",
                userId, conversationId, content.length());

        Document doc = new Document(content, Map.of(
                "userId", userId,
                "conversationId", conversationId,
                "type", "conversation",
                "timestamp", LocalDateTime.now().toString()
        ));
        vectorStore.add(List.of(doc));

        log.info("[Milvus] 对话片段存储完成 | userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 语义检索相似历史对话
     */
    public List<Document> searchRelevantConversations(String userId, String query, int topK) {
        log.info("[Milvus] 检索相似对话 | userId={}, topK={}", userId, topK);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("userId == '" + userId + "' && type == 'conversation'")
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        log.info("[Milvus] 检索到 {} 条相似对话 | userId={}", results.size(), userId);
        return results;
    }

    /**
     * 存储提取的事实到 Milvus
     */
    public void storeFactDocument(String userId, String factText, String category,
                                   byte importance, Long factId) {
        log.info("[Milvus] 存储事实文档 | userId={}, factId={}, category={}, text={}",
                userId, factId, category,
                factText.length() > 50 ? factText.substring(0, 50) + "..." : factText);

        Document doc = new Document(factText, Map.of(
                "userId", userId,
                "type", "fact",
                "category", category,
                "importance", String.valueOf(importance),
                "factId", String.valueOf(factId)
        ));
        vectorStore.add(List.of(doc));

        log.info("[Milvus] 事实文档存储完成 | userId={}, factId={}", userId, factId);
    }

    /**
     * 语义检索相关事实
     */
    public List<Document> searchRelevantFacts(String userId, String query, int topK) {
        log.info("[Milvus] 检索相关事实 | userId={}, topK={}, query={}",
                userId, topK, query.length() > 40 ? query.substring(0, 40) + "..." : query);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("userId == '" + userId + "' && type == 'fact'")
                .build();
        List<Document> results = vectorStore.similaritySearch(request);

        log.info("[Milvus] 检索到 {} 条相关事实 | userId={}", results.size(), userId);
        for (Document doc : results) {
            log.debug("[Milvus]   - 内容={}, metadata={}", doc.getText(), doc.getMetadata());
        }
        return results;
    }

    /**
     * 删除指定会话的所有向量文档
     */
    public void deleteConversationDocuments(String conversationId) {
        log.info("[Milvus] 删除会话向量文档 | conversationId={}", conversationId);
        vectorStore.delete("conversationId == '" + conversationId + "'");
        log.info("[Milvus] 会话向量文档删除完成 | conversationId={}", conversationId);
    }
}
