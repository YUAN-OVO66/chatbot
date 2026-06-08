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

                // 5b. 语义相似度去重：在 Milvus 中检索 top-5 相似事实
                boolean isDuplicate = false;
                try {
                    List<org.springframework.ai.document.Document> similarDocs =
                            semanticMemoryService.searchRelevantFacts(userId, text, 5);

                    for (org.springframework.ai.document.Document similarDoc : similarDocs) {
                        // 优先使用 Milvus 向量余弦相似度
                        Double vectorScore = null;
                        Object scoreObj = similarDoc.getMetadata().get("score");
                        if (scoreObj instanceof Number) {
                            vectorScore = ((Number) scoreObj).doubleValue();
                        }

                        double similarity;
                        if (vectorScore != null) {
                            similarity = vectorScore;
                        } else {
                            // fallback: 手工文本相似度
                            similarity = calculateTextSimilarity(text, similarDoc.getText());
                        }

                        log.info("[Extract] 步骤5: 语义去重检查 | new=\"{}\", exist=\"{}\", similarity={}, source={}",
                                text.length() > 30 ? text.substring(0, 30) + "..." : text,
                                similarDoc.getText().length() > 30 ? similarDoc.getText().substring(0, 30) + "..." : similarDoc.getText(),
                                String.format("%.4f", similarity),
                                vectorScore != null ? "vector" : "text");

                        if (similarity >= 0.85) {
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
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[Extract] 步骤5: 语义去重检查失败, 跳过该事实以避免重复 | error={}", e.getMessage());
                    continue;
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

        if (facts.isEmpty()) {
            log.info("[Memory] 无活跃事实，跳过整合");
            return;
        }

        java.util.Set<Long> removedIds = new java.util.HashSet<>();
        int removed = 0;

        // 第一轮：删除重要程度 < 5 的事实
        for (UserMemoryFact fact : facts) {
            if (fact.getImportance() < 5) {
                log.info("[Memory] 低重要性删除 | id={}, imp={}, text={}", fact.getId(), fact.getImportance(), fact.getFactText());
                fact.setIsActive(false);
                factRepository.save(fact);
                try {
                    semanticMemoryService.deleteFactDocument(fact.getId());
                } catch (Exception e) {
                    log.warn("[Memory] 删除向量失败 | factId={}", fact.getId());
                }
                removedIds.add(fact.getId());
                removed++;
            }
        }

        // 第二轮：语义去重（仅处理剩余的高重要性事实）
        List<UserMemoryFact> remaining = facts.stream()
                .filter(f -> !removedIds.contains(f.getId())).toList();

        for (UserMemoryFact fact : remaining) {
            if (removedIds.contains(fact.getId())) continue;

            try {
                List<org.springframework.ai.document.Document> similarDocs =
                        semanticMemoryService.searchRelevantFacts(userId, fact.getFactText(), 10);

                for (org.springframework.ai.document.Document doc : similarDocs) {
                    Object factIdObj = doc.getMetadata().get("factId");
                    if (factIdObj == null) continue;

                    Long similarFactId = Long.parseLong(factIdObj.toString());
                    if (similarFactId.equals(fact.getId()) || removedIds.contains(similarFactId)) continue;

                    Double score = null;
                    Object scoreObj = doc.getMetadata().get("score");
                    if (scoreObj instanceof Number) {
                        score = ((Number) scoreObj).doubleValue();
                    }
                    String source;
                    double similarity;
                    if (score != null) {
                        similarity = score;
                        source = "vector";
                    } else {
                        similarity = calculateTextSimilarity(fact.getFactText(), doc.getText());
                        source = "text";
                    }

                    log.info("[Memory] 整合去重检查 | exist=\"{}\", similarity={}, source={}",
                            doc.getText().length() > 30 ? doc.getText().substring(0, 30) + "..." : doc.getText(),
                            String.format("%.4f", similarity), source);

                    if (similarity >= 0.70) {
                        Optional<UserMemoryFact> similarOpt = factRepository.findById(similarFactId);
                        if (similarOpt.isPresent() && similarOpt.get().getIsActive()) {
                            UserMemoryFact similar = similarOpt.get();

                            UserMemoryFact toRemove;
                            if (fact.getImportance() >= similar.getImportance()) {
                                toRemove = similar;
                            } else {
                                toRemove = fact;
                            }

                            log.info("[Memory] 语义重复, 删除 | remove=\"{}\"(imp={}), keep=\"{}\"(imp={}), sim={}",
                                    toRemove.getFactText(), toRemove.getImportance(),
                                    toRemove.getId().equals(fact.getId()) ? similar.getFactText() : fact.getFactText(),
                                    toRemove.getId().equals(fact.getId()) ? similar.getImportance() : fact.getImportance(),
                                    String.format("%.4f", similarity));

                            toRemove.setIsActive(false);
                            factRepository.save(toRemove);
                            try {
                                semanticMemoryService.deleteFactDocument(toRemove.getId());
                            } catch (Exception e) {
                                log.warn("[Memory] 整合时删除向量失败 | factId={}", toRemove.getId());
                            }
                            removedIds.add(toRemove.getId());
                            removed++;

                            if (toRemove.getId().equals(fact.getId())) break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Memory] 整合检查失败 | factId={}, error={}", fact.getId(), e.getMessage());
            }
        }
        log.info("[Memory] 记忆整合完成 | userId={}, 去除 {} 条", userId, removed);
    }

    /**
     * 计算两段文本的相似度（0.0 ~ 1.0）
     * 综合字符 bigram Jaccard、编辑距离和共同字符比
     */
    private double calculateTextSimilarity(String text1, String text2) {
        if (text1.equals(text2)) return 1.0;

        // 1. 字符 bigram Jaccard（对中文语义更敏感）
        java.util.Set<String> bigrams1 = extractCharBigrams(text1);
        java.util.Set<String> bigrams2 = extractCharBigrams(text2);

        java.util.Set<String> intersection = new java.util.HashSet<>(bigrams1);
        intersection.retainAll(bigrams2);
        java.util.Set<String> union = new java.util.HashSet<>(bigrams1);
        union.addAll(bigrams2);
        double bigramJaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();

        // 2. 共同字符比（忽略顺序，捕捉主题重叠）
        java.util.Set<Character> chars1 = new java.util.HashSet<>();
        for (char c : text1.toCharArray()) chars1.add(c);
        java.util.Set<Character> chars2 = new java.util.HashSet<>();
        for (char c : text2.toCharArray()) chars2.add(c);
        java.util.Set<Character> charInter = new java.util.HashSet<>(chars1);
        charInter.retainAll(chars2);
        java.util.Set<Character> charUnion = new java.util.HashSet<>(chars1);
        charUnion.addAll(chars2);
        double charJaccard = charUnion.isEmpty() ? 0.0 : (double) charInter.size() / charUnion.size();

        // 3. 归一化编辑距离
        int maxLen = Math.max(text1.length(), text2.length());
        double editSimilarity = maxLen == 0 ? 1.0 : 1.0 - ((double) editDistance(text1, text2) / maxLen);

        // 加权: bigram 50%, 字符重叠 25%, 编辑距离 25%
        return bigramJaccard * 0.5 + charJaccard * 0.25 + editSimilarity * 0.25;
    }

    private java.util.Set<String> extractCharBigrams(String text) {
        java.util.Set<String> bigrams = new java.util.HashSet<>();
        // 去除空白和标点，提取有意义的字符
        String cleaned = text.replaceAll("[\\s\\p{Punct}，。、；：！？（）【】《》\"']", "");
        for (int i = 0; i < cleaned.length() - 1; i++) {
            bigrams.add(cleaned.substring(i, i + 2));
        }
        if (cleaned.length() == 1) {
            bigrams.add(cleaned);
        }
        return bigrams;
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

    /**
     * 手动创建记忆事实（含语义去重 + 向量化存储）
     */
    @Transactional
    public UserMemoryFact createFact(String userId, String factText, String category, Byte importance) {
        log.info("[Memory] 手动创建事实 | userId={}, category={}, text={}", userId, category, factText);

        // 精确匹配去重
        Optional<UserMemoryFact> exactMatch = factRepository
                .findByUserIdAndFactTextAndIsActive(userId, factText, true);
        if (exactMatch.isPresent()) {
            log.info("[Memory] 精确匹配已存在, 返回已有事实 | id={}", exactMatch.get().getId());
            return exactMatch.get();
        }

        // 语义去重
        try {
            List<org.springframework.ai.document.Document> similarDocs =
                    semanticMemoryService.searchRelevantFacts(userId, factText, 5);
            for (org.springframework.ai.document.Document doc : similarDocs) {
                Double score = null;
                Object scoreObj = doc.getMetadata().get("score");
                if (scoreObj instanceof Number) {
                    score = ((Number) scoreObj).doubleValue();
                }
                double similarity = score != null ? score : calculateTextSimilarity(factText, doc.getText());
                if (similarity >= 0.85) {
                    Object factIdObj = doc.getMetadata().get("factId");
                    if (factIdObj != null) {
                        Optional<UserMemoryFact> existing = factRepository.findById(Long.parseLong(factIdObj.toString()));
                        if (existing.isPresent() && existing.get().getIsActive()) {
                            log.info("[Memory] 语义重复, 返回已有事实 | id={}, similarity={}", existing.get().getId(), String.format("%.4f", similarity));
                            return existing.get();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Memory] 语义去重检查失败 | error={}", e.getMessage());
        }

        // 创建新事实
        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(userId);
        fact.setFactText(factText);
        fact.setCategory(category != null ? category : "general");
        fact.setImportance(importance != null ? importance : 5);
        fact = factRepository.save(fact);

        // 向量化存储
        try {
            semanticMemoryService.storeFactDocument(userId, factText, fact.getCategory(), fact.getImportance(), fact.getId());
        } catch (Exception e) {
            log.warn("[Memory] 向量化存储失败, MySQL 记录已保存 | error={}", e.getMessage());
        }

        log.info("[Memory] 事实创建完成 | id={}, text={}", fact.getId(), factText);
        return fact;
    }

    /**
     * 更新记忆事实（文本/分类/重要性变更时重新向量化）
     */
    @Transactional
    public UserMemoryFact updateFact(Long factId, String factText, String category, Byte importance) {
        log.info("[Memory] 更新事实 | factId={}", factId);

        UserMemoryFact fact = factRepository.findById(factId)
                .orElseThrow(() -> new RuntimeException("事实不存在: " + factId));

        boolean textChanged = factText != null && !factText.equals(fact.getFactText());

        if (factText != null) fact.setFactText(factText);
        if (category != null) fact.setCategory(category);
        if (importance != null) fact.setImportance(importance);
        fact = factRepository.save(fact);

        // 文本变更时：先删旧向量，再存新向量
        if (textChanged) {
            try {
                semanticMemoryService.deleteFactDocument(factId);
                semanticMemoryService.storeFactDocument(
                        fact.getUserId(), fact.getFactText(), fact.getCategory(), fact.getImportance(), fact.getId());
                log.info("[Memory] 事实向量化已更新 | factId={}", factId);
            } catch (Exception e) {
                log.warn("[Memory] 重新向量化失败 | error={}", e.getMessage());
            }
        }

        log.info("[Memory] 事实更新完成 | factId={}", factId);
        return fact;
    }

    /**
     * 重置用户所有记忆：事实、偏好、向量
     */
    @Transactional
    public void resetAllMemory(String userId) {
        log.info("[Memory] ========== 重置所有记忆开始 | userId={} ==========", userId);

        // 1. 删除所有事实（MySQL + Milvus）
        List<UserMemoryFact> facts = factRepository.findByUserId(userId);
        for (UserMemoryFact fact : facts) {
            try {
                semanticMemoryService.deleteFactDocument(fact.getId());
            } catch (Exception e) {
                log.warn("[Memory] 删除向量失败 | factId={}", fact.getId());
            }
        }
        factRepository.deleteAll(facts);
        log.info("[Memory] 已删除 {} 条事实", facts.size());

        // 2. 删除所有偏好
        List<UserPreference> prefs = preferenceRepository.findByUserIdOrderByConfidenceDesc(userId);
        preferenceRepository.deleteAll(prefs);
        log.info("[Memory] 已删除 {} 条偏好", prefs.size());

        log.info("[Memory] ========== 重置所有记忆完成 | userId={} ==========", userId);
    }
}
