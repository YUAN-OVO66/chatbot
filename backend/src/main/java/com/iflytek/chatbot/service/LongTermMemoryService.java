package com.iflytek.chatbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.chatbot.entity.MemoryExtractionLog;
import com.iflytek.chatbot.entity.UserMemoryFact;
import com.iflytek.chatbot.entity.UserPreference;
import com.iflytek.chatbot.repository.MemoryExtractionLogRepository;
import com.iflytek.chatbot.repository.UserMemoryFactRepository;
import com.iflytek.chatbot.repository.UserPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    private final UserMemoryFactRepository factRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final MemoryExtractionLogRepository extractionLogRepository;
    private final SemanticMemoryService semanticMemoryService;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public LongTermMemoryService(UserMemoryFactRepository factRepository,
                                  UserPreferenceRepository preferenceRepository,
                                  MemoryExtractionLogRepository extractionLogRepository,
                                  SemanticMemoryService semanticMemoryService,
                                  @Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  ObjectMapper objectMapper) {
        this.factRepository = factRepository;
        this.preferenceRepository = preferenceRepository;
        this.extractionLogRepository = extractionLogRepository;
        this.semanticMemoryService = semanticMemoryService;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 从 Milvus 语义检索相关事实，再回查 MySQL 获取完整记录
     */
    public List<UserMemoryFact> retrieveRelevantFacts(String userId, String query, int topK) {
        log.info("[Memory] >>> 语义检索相关事实 | userId={}, topK={}, query={}",
                userId, topK, query.length() > 40 ? query.substring(0, 40) + "..." : query);

        List<org.springframework.ai.document.Document> docs =
                semanticMemoryService.searchRelevantFacts(userId, query, topK);
        log.info("[Memory] Milvus 返回 {} 条向量匹配结果", docs.size());

        List<UserMemoryFact> results = docs.stream()
                .map(doc -> {
                    Object factIdObj = doc.getMetadata().get("factId");
                    String factId = factIdObj != null ? factIdObj.toString() : null;
                    if (factId != null) {
                        return factRepository.findById(Long.parseLong(factId)).orElse(null);
                    }
                    return null;
                })
                .filter(f -> f != null && f.getIsActive())
                .toList();

        log.info("[Memory] <<< 最终返回 {} 条有效事实", results.size());
        for (UserMemoryFact f : results) {
            log.info("[Memory]   - [{}] {} (重要性={})", f.getCategory(), f.getFactText(), f.getImportance());
        }
        return results;
    }

    /**
     * 调用 LLM 从对话中提取事实和偏好
     */
    @Transactional
    public void extractFacts(String conversationId, String userId, List<Message> messages) {
        log.info("[Extract] ========== 事实提取开始 ==========");
        log.info("[Extract] conversationId={}, userId={}, 消息数={}", conversationId, userId, messages.size());

        MemoryExtractionLog extractionLog = new MemoryExtractionLog();
        extractionLog.setConversationId(conversationId);
        extractionLog.setUserId(userId);
        extractionLog.setExtractionType("fact");
        extractionLog.setInputMessageCount(messages.size());

        try {
            // 步骤1: 拼接对话文本
            String conversationText = buildConversationText(messages);
            log.info("[Extract] 步骤1: 对话文本拼接完成, 长度={}字符", conversationText.length());

            // 步骤2: 构造提取 Prompt
            String extractionPrompt = """
                    Analyze the following conversation and extract important facts and preferences about the user.
                    Return a JSON object with two arrays:

                    {
                      "facts": [
                        {"text": "...", "category": "personal_info|work|habit|general", "importance": 1-10}
                      ],
                      "preferences": [
                        {"key": "...", "value": "...", "confidence": 0.0-1.0}
                      ]
                    }

                    Only extract facts that are likely to be useful in future conversations.
                    Do NOT extract trivial or ephemeral information.
                    The response must be valid JSON only, no markdown or explanation.

                    Conversation:
                    """ + conversationText;
            log.info("[Extract] 步骤2: 提取 Prompt 构造完成");

            // 步骤3: 调用 DeepSeek
            log.info("[Extract] 步骤3: >>> 调用 DeepSeek 进行事实提取...");
            long start = System.currentTimeMillis();
            var response = chatModel.call(new Prompt(new UserMessage(extractionPrompt)));
            String content = response.getResult().getOutput().getText().trim();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Extract] 步骤3: <<< DeepSeek 返回 | 耗时={}ms, 响应长度={}字符", elapsed, content.length());
            log.debug("[Extract] 步骤3: LLM 原始响应:\n{}", content);

            if (content.startsWith("```")) {
                content = content.replaceAll("^```json\\s*", "").replaceAll("\\s*```$", "");
            }

            // 步骤4: 解析 JSON
            Map<String, Object> extracted = objectMapper.readValue(content, new TypeReference<>() {});
            List<Map<String, Object>> facts = (List<Map<String, Object>>) extracted.getOrDefault("facts", List.of());
            List<Map<String, Object>> preferences = (List<Map<String, Object>>) extracted.getOrDefault("preferences", List.of());
            log.info("[Extract] 步骤4: JSON 解析完成 | facts={}, preferences={}", facts.size(), preferences.size());

            int extractedCount = 0;

            // 步骤5: 持久化事实（已存在则更新重要性，不存在则新建）
            for (Map<String, Object> factData : facts) {
                String text = (String) factData.get("text");
                String category = (String) factData.getOrDefault("category", "general");
                byte importance = ((Number) factData.getOrDefault("importance", 5)).byteValue();

                Optional<UserMemoryFact> existing = factRepository
                        .findByUserIdAndFactTextAndIsActive(userId, text, true);

                if (existing.isPresent()) {
                    UserMemoryFact fact = existing.get();
                    if (importance > fact.getImportance()) {
                        fact.setImportance(importance);
                        factRepository.save(fact);
                        log.info("[Extract] 步骤5: 事实已存在, 更新重要性 | id={}, importance {}→{}", fact.getId(), fact.getImportance(), importance);
                    } else {
                        log.info("[Extract] 步骤5: 事实已存在, 跳过 | id={}, text={}", fact.getId(), text);
                    }
                } else {
                    UserMemoryFact fact = new UserMemoryFact();
                    fact.setUserId(userId);
                    fact.setConversationId(conversationId);
                    fact.setFactText(text);
                    fact.setCategory(category);
                    fact.setImportance(importance);
                    fact = factRepository.save(fact);
                    log.info("[Extract] 步骤5: 新事实存入 MySQL | id={}, category={}, text={}", fact.getId(), category, text);

                    semanticMemoryService.storeFactDocument(userId, text, category, importance, fact.getId());
                    log.info("[Extract] 步骤5: 事实向量化存入 Milvus | factId={}", fact.getId());
                }
                extractedCount++;
            }

            // 步骤6: 持久化偏好
            for (Map<String, Object> prefData : preferences) {
                String key = (String) prefData.get("key");
                String value = (String) prefData.get("value");
                double confidence = ((Number) prefData.getOrDefault("confidence", 0.5)).doubleValue();

                UserPreference pref = preferenceRepository.findByUserIdAndPreferenceKey(userId, key)
                        .orElse(new UserPreference());
                pref.setUserId(userId);
                pref.setPreferenceKey(key);
                pref.setPreferenceValue(value);
                pref.setConfidence(BigDecimal.valueOf(confidence));
                pref.setSource("extracted");
                preferenceRepository.save(pref);
                log.info("[Extract] 步骤6: 偏好存入 MySQL | key={}, value={}, confidence={}", key, value, confidence);
                extractedCount++;
            }

            extractionLog.setExtractedCount(extractedCount);
            extractionLog.setStatus("success");
            log.info("[Extract] ========== 事实提取完成 | 共提取 {} 条 ==========", extractedCount);

        } catch (Exception e) {
            extractionLog.setStatus("failed");
            extractionLog.setErrorMessage(e.getMessage());
            log.error("[Extract] ========== 事实提取失败 | error={} ==========", e.getMessage(), e);
        } finally {
            extractionLogRepository.save(extractionLog);
        }
    }

    @Transactional
    public void consolidateMemories(String userId) {
        log.info("[Memory] 开始记忆整合 | userId={}", userId);
        List<UserMemoryFact> facts = factRepository
                .findByUserIdAndIsActiveOrderByImportanceDescCreatedAtDesc(userId, true);

        int removed = 0;
        for (int i = 0; i < facts.size(); i++) {
            for (int j = i + 1; j < facts.size(); j++) {
                if (facts.get(i).getFactText().equalsIgnoreCase(facts.get(j).getFactText())) {
                    facts.get(j).setIsActive(false);
                    factRepository.save(facts.get(j));
                    removed++;
                }
            }
        }
        log.info("[Memory] 记忆整合完成 | userId={}, 去除 {} 条重复事实", userId, removed);
    }

    private String buildConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof UserMessage userMsg) {
                sb.append("User: ").append(userMsg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage assistantMsg) {
                sb.append("Assistant: ").append(assistantMsg.getText()).append("\n");
            }
        }
        return sb.toString();
    }
}
