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

            // 步骤5: 持久化事实（语义去重 + 精确去重）
            for (Map<String, Object> factData : facts) {
                String text = (String) factData.get("text");
                String category = (String) factData.getOrDefault("category", "general");
                byte importance = ((Number) factData.getOrDefault("importance", 5)).byteValue();

                // 5a. 精确匹配去重
                Optional<UserMemoryFact> exactMatch = factRepository
                        .findByUserIdAndFactTextAndIsActive(userId, text, true);

                if (exactMatch.isPresent()) {
                    UserMemoryFact fact = exactMatch.get();
                    if (importance > fact.getImportance()) {
                        fact.setImportance(importance);
                        factRepository.save(fact);
                        log.info("[Extract] 步骤5: 精确匹配已存在, 更新重要性 | id={}, importance {}→{}", fact.getId(), fact.getImportance(), importance);
                    } else {
                        log.info("[Extract] 步骤5: 精确匹配已存在, 跳过 | id={}, text={}", fact.getId(), text);
                    }
                    extractedCount++;
                    continue;
                }

                // 5b. 语义相似度去重：在 Milvus 中检索相似事实
                boolean isDuplicate = false;
                try {
                    List<org.springframework.ai.document.Document> similarDocs =
                            semanticMemoryService.searchRelevantFacts(userId, text, 1);

                    if (!similarDocs.isEmpty()) {
                        org.springframework.ai.document.Document similarDoc = similarDocs.get(0);
                        String similarText = similarDoc.getText();

                        // 计算文本相似度（基于归一化编辑距离 + 关键词重叠）
                        double similarity = calculateTextSimilarity(text, similarText);
                        log.info("[Extract] 步骤5: 语义去重检查 | new=\"{}\", exist=\"{}\", similarity={}",
                                text, similarText, similarity);

                        if (similarity >= 0.75) {
                            // 高度相似，视为重复
                            Object factIdObj = similarDoc.getMetadata().get("factId");
                            if (factIdObj != null) {
                                Long existingFactId = Long.parseLong(factIdObj.toString());
                                Optional<UserMemoryFact> existingOpt = factRepository.findById(existingFactId);
                                if (existingOpt.isPresent() && existingOpt.get().getIsActive()) {
                                    UserMemoryFact existing = existingOpt.get();
                                    if (importance > existing.getImportance()) {
                                        existing.setImportance(importance);
                                        factRepository.save(existing);
                                        log.info("[Extract] 步骤5: 语义重复, 更新重要性 | id={}, text=\"{}\"", existing.getId(), existing.getFactText());
                                    } else {
                                        log.info("[Extract] 步骤5: 语义重复, 跳过 | exist=\"{}\"", existing.getFactText());
                                    }
                                    isDuplicate = true;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[Extract] 步骤5: 语义去重检查失败, 降级为直接插入 | error={}", e.getMessage());
                }

                if (isDuplicate) {
                    extractedCount++;
                    continue;
                }

                // 5c. 无重复，新建事实
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

        // 标记已删除的 fact id，避免处理过程中重复比较
        java.util.Set<Long> removedIds = new java.util.HashSet<>();
        int removed = 0;

        for (int i = 0; i < facts.size(); i++) {
            if (removedIds.contains(facts.get(i).getId())) continue;

            for (int j = i + 1; j < facts.size(); j++) {
                if (removedIds.contains(facts.get(j).getId())) continue;

                UserMemoryFact fi = facts.get(i);
                UserMemoryFact fj = facts.get(j);

                // 精确匹配 或 语义相似度 >= 0.75 视为重复
                boolean isDuplicate = fi.getFactText().equalsIgnoreCase(fj.getFactText());
                if (!isDuplicate) {
                    double sim = calculateTextSimilarity(fi.getFactText(), fj.getFactText());
                    if (sim >= 0.75) {
                        isDuplicate = true;
                        log.info("[Memory] 语义重复 | \"{}\" vs \"{}\" (sim={})", fi.getFactText(), fj.getFactText(), sim);
                    }
                }

                if (isDuplicate) {
                    fj.setIsActive(false);
                    factRepository.save(fj);
                    removedIds.add(fj.getId());
                    removed++;
                }
            }
        }
        log.info("[Memory] 记忆整合完成 | userId={}, 去除 {} 条重复事实", userId, removed);
    }

    /**
     * 计算两段文本的相似度（0.0 ~ 1.0）
     * 综合关键词重叠率和编辑距离
     */
    private double calculateTextSimilarity(String text1, String text2) {
        if (text1.equals(text2)) return 1.0;

        // 提取中文关键词（2-4字）和英文单词
        java.util.Set<String> words1 = extractKeywords(text1);
        java.util.Set<String> words2 = extractKeywords(text2);

        if (words1.isEmpty() && words2.isEmpty()) {
            return text1.equalsIgnoreCase(text2) ? 1.0 : 0.0;
        }

        // Jaccard 相似度
        java.util.Set<String> intersection = new java.util.HashSet<>(words1);
        intersection.retainAll(words2);
        java.util.Set<String> union = new java.util.HashSet<>(words1);
        union.addAll(words2);
        double jaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();

        // 归一化编辑距离
        int maxLen = Math.max(text1.length(), text2.length());
        double editSimilarity = maxLen == 0 ? 1.0 : 1.0 - ((double) editDistance(text1, text2) / maxLen);

        // 加权: 关键词重叠占 70%, 编辑距离占 30%
        return jaccard * 0.7 + editSimilarity * 0.3;
    }

    private java.util.Set<String> extractKeywords(String text) {
        java.util.Set<String> words = new java.util.HashSet<>();
        // 中文 2-4 字词
        java.util.regex.Matcher cnMatcher = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]{2,4}").matcher(text);
        while (cnMatcher.find()) {
            words.add(cnMatcher.group());
        }
        // 英文单词
        java.util.regex.Matcher enMatcher = java.util.regex.Pattern.compile("[a-zA-Z]+").matcher(text.toLowerCase());
        while (enMatcher.find()) {
            words.add(enMatcher.group());
        }
        return words;
    }

    private int editDistance(String s1, String s2) {
        int m = s1.length(), n = s2.length();
        int[] prev = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            int[] curr = new int[n + 1];
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    curr[j] = prev[j - 1];
                } else {
                    curr[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
                }
            }
            prev = curr;
        }
        return prev[n];
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
