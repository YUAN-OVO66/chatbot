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
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    private static final double DEDUP_THRESHOLD_EXTRACT = 0.85;
    private static final double DEDUP_THRESHOLD_CONSOLIDATE = 0.70;

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

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<UserMemoryFact> retrieveRelevantFacts(String userId, String query, int topK) {
        log.debug("[Memory] retrieveRelevantFacts | userId={}, topK={}", userId, topK);

        List<Document> docs = semanticMemoryService.searchRelevantFacts(userId, query, topK);

        List<UserMemoryFact> results = docs.stream()
                .map(doc -> {
                    Object id = doc.getMetadata().get("factId");
                    return id != null ? factRepository.findById(Long.parseLong(id.toString())).orElse(null) : null;
                })
                .filter(f -> f != null && f.getIsActive())
                .toList();

        log.info("[Memory] retrieveRelevantFacts | userId={}, returned={}", userId, results.size());
        return results;
    }

    @Transactional
    public void extractFacts(String conversationId, String userId, List<Message> messages) {
        log.info("[Extract] start | conversationId={}, userId={}, msgs={}", conversationId, userId, messages.size());

        MemoryExtractionLog extractionLog = buildExtractionLog(conversationId, userId, messages.size());
        int extractedCount = 0;

        try {
            String conversationText = buildConversationText(messages);
            Map<String, Object> extracted = callLlmForExtraction(conversationText);

            List<Map<String, Object>> facts = castList(extracted.getOrDefault("facts", List.of()));
            List<Map<String, Object>> preferences = castList(extracted.getOrDefault("preferences", List.of()));
            log.debug("[Extract] LLM returned | facts={}, prefs={}", facts.size(), preferences.size());

            extractedCount += persistFacts(conversationId, userId, facts);
            extractedCount += persistPreferences(userId, preferences);

            extractionLog.setExtractedCount(extractedCount);
            extractionLog.setStatus("success");
            log.info("[Extract] done | conversationId={}, extracted={}", conversationId, extractedCount);

        } catch (Exception e) {
            extractionLog.setStatus("failed");
            extractionLog.setErrorMessage(e.getMessage());
            log.error("[Extract] failed | conversationId={}, error={}", conversationId, e.getMessage(), e);
        } finally {
            extractionLogRepository.save(extractionLog);
        }
    }

    @Transactional
    public void consolidateMemories(String userId) {
        log.info("[Memory] consolidate start | userId={}", userId);
        List<UserMemoryFact> facts = factRepository
                .findByUserIdAndIsActiveOrderByImportanceDescCreatedAtDesc(userId, true);

        if (facts.isEmpty()) {
            log.debug("[Memory] no active facts, skip consolidation | userId={}", userId);
            return;
        }

        Set<Long> removedIds = new HashSet<>();
        int removed = 0;

        removed += removeLowImportanceFacts(facts, removedIds);
        removed += removeSemanticallyDuplicateFacts(userId, facts, removedIds);

        log.info("[Memory] consolidate done | userId={}, removed={}", userId, removed);
    }

    @Transactional
    public UserMemoryFact createFact(String userId, String factText, String category, Byte importance) {
        log.info("[Memory] createFact | userId={}, category={}", userId, category);

        Optional<UserMemoryFact> exactMatch = factRepository
                .findByUserIdAndFactTextAndIsActive(userId, factText, true);
        if (exactMatch.isPresent()) {
            log.debug("[Memory] exact duplicate, returning existing | id={}", exactMatch.get().getId());
            return exactMatch.get();
        }

        Optional<UserMemoryFact> semantic = findSemanticDuplicate(userId, factText, DEDUP_THRESHOLD_EXTRACT);
        if (semantic.isPresent()) {
            log.debug("[Memory] semantic duplicate, returning existing | id={}", semantic.get().getId());
            return semantic.get();
        }

        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(userId);
        fact.setFactText(factText);
        fact.setCategory(category != null ? category : "general");
        fact.setImportance(importance != null ? importance : 5);
        fact = factRepository.save(fact);

        try {
            semanticMemoryService.storeFactDocument(userId, factText, fact.getCategory(), fact.getImportance(), fact.getId());
        } catch (Exception e) {
            log.warn("[Memory] vector store failed, MySQL record saved | error={}", e.getMessage());
        }

        log.info("[Memory] createFact done | id={}", fact.getId());
        return fact;
    }

    @Transactional
    public UserMemoryFact updateFact(Long factId, String factText, String category, Byte importance) {
        log.info("[Memory] updateFact | factId={}", factId);

        UserMemoryFact fact = factRepository.findById(factId)
                .orElseThrow(() -> new RuntimeException("事实不存在: " + factId));

        boolean textChanged = factText != null && !factText.equals(fact.getFactText());

        if (factText != null) fact.setFactText(factText);
        if (category != null) fact.setCategory(category);
        if (importance != null) fact.setImportance(importance);
        fact = factRepository.save(fact);

        if (textChanged) {
            try {
                semanticMemoryService.deleteFactDocument(factId);
                semanticMemoryService.storeFactDocument(
                        fact.getUserId(), fact.getFactText(), fact.getCategory(), fact.getImportance(), fact.getId());
            } catch (Exception e) {
                log.warn("[Memory] re-vectorization failed | error={}", e.getMessage());
            }
        }

        log.info("[Memory] updateFact done | factId={}", factId);
        return fact;
    }

    @Transactional
    public void resetAllMemory(String userId) {
        log.info("[Memory] resetAllMemory | userId={}", userId);

        List<UserMemoryFact> facts = factRepository.findByUserId(userId);
        for (UserMemoryFact fact : facts) {
            try {
                semanticMemoryService.deleteFactDocument(fact.getId());
            } catch (Exception e) {
                log.warn("[Memory] delete vector failed | factId={}", fact.getId());
            }
        }
        factRepository.deleteAll(facts);

        List<UserPreference> prefs = preferenceRepository.findByUserIdOrderByConfidenceDesc(userId);
        preferenceRepository.deleteAll(prefs);

        log.info("[Memory] resetAllMemory done | userId={}, facts={}, prefs={}", userId, facts.size(), prefs.size());
    }

    // -------------------------------------------------------------------------
    // extractFacts helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> callLlmForExtraction(String conversationText) throws Exception {
        String prompt = """
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

        long start = System.currentTimeMillis();
        var response = chatModel.call(new Prompt(new UserMessage(prompt)));
        String content = response.getResult().getOutput().getText().trim();
        log.debug("[Extract] LLM call done | elapsed={}ms, len={}", System.currentTimeMillis() - start, content.length());
        log.debug("[Extract] LLM raw response:\n{}", content);

        if (content.startsWith("```")) {
            content = content.replaceAll("^```json\\s*", "").replaceAll("\\s*```$", "");
        }
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    private int persistFacts(String conversationId, String userId, List<Map<String, Object>> facts) {
        int count = 0;
        for (Map<String, Object> factData : facts) {
            String text = (String) factData.get("text");
            String category = (String) factData.getOrDefault("category", "general");
            byte importance = ((Number) factData.getOrDefault("importance", 5)).byteValue();

            try {
                persistFact(conversationId, userId, text, category, importance);
            } catch (Exception e) {
                log.warn("[Extract] skip fact due to error | text={}, error={}", text, e.getMessage());
                continue;
            }
            count++;
        }
        return count;
    }

    private void persistFact(String conversationId, String userId,
                              String text, String category, byte importance) {
        // 精确匹配去重
        Optional<UserMemoryFact> exactMatch = factRepository
                .findByUserIdAndFactTextAndIsActive(userId, text, true);
        if (exactMatch.isPresent()) {
            updateImportanceIfHigher(exactMatch.get(), importance);
            log.debug("[Extract] exact duplicate | id={}", exactMatch.get().getId());
            return;
        }

        // 语义去重
        List<Document> similarDocs = semanticMemoryService.searchRelevantFacts(userId, text, 5);
        for (Document similarDoc : similarDocs) {
            double similarity = resolveDocSimilarity(text, similarDoc);
            log.debug("[Extract] dedup check | similarity={}, threshold={}", String.format("%.4f", similarity), DEDUP_THRESHOLD_EXTRACT);
            if (similarity >= DEDUP_THRESHOLD_EXTRACT) {
                Object factIdObj = similarDoc.getMetadata().get("factId");
                if (factIdObj != null) {
                    factRepository.findById(Long.parseLong(factIdObj.toString()))
                            .filter(UserMemoryFact::getIsActive)
                            .ifPresent(existing -> updateImportanceIfHigher(existing, importance));
                }
                return;
            }
        }

        // 新事实
        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(userId);
        fact.setConversationId(conversationId);
        fact.setFactText(text);
        fact.setCategory(category);
        fact.setImportance(importance);
        fact = factRepository.save(fact);
        semanticMemoryService.storeFactDocument(userId, text, category, importance, fact.getId());
        log.debug("[Extract] new fact saved | id={}, category={}", fact.getId(), category);
    }

    private int persistPreferences(String userId, List<Map<String, Object>> preferences) {
        int count = 0;
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
            log.debug("[Extract] preference saved | key={}, confidence={}", key, confidence);
            count++;
        }
        return count;
    }

    private void updateImportanceIfHigher(UserMemoryFact fact, byte importance) {
        if (importance > fact.getImportance()) {
            fact.setImportance(importance);
            factRepository.save(fact);
            log.debug("[Extract] updated importance | id={}, importance={}", fact.getId(), importance);
        }
    }

    // -------------------------------------------------------------------------
    // consolidateMemories helpers
    // -------------------------------------------------------------------------

    private int removeLowImportanceFacts(List<UserMemoryFact> facts, Set<Long> removedIds) {
        int removed = 0;
        for (UserMemoryFact fact : facts) {
            if (fact.getImportance() < 5) {
                deactivateFact(fact);
                removedIds.add(fact.getId());
                removed++;
                log.debug("[Memory] removed low-importance | id={}, imp={}", fact.getId(), fact.getImportance());
            }
        }
        return removed;
    }

    private int removeSemanticallyDuplicateFacts(String userId, List<UserMemoryFact> facts, Set<Long> removedIds) {
        int removed = 0;
        List<UserMemoryFact> remaining = facts.stream().filter(f -> !removedIds.contains(f.getId())).toList();

        for (UserMemoryFact fact : remaining) {
            if (removedIds.contains(fact.getId())) continue;

            try {
                List<Document> similarDocs = semanticMemoryService.searchRelevantFacts(userId, fact.getFactText(), 10);
                for (Document doc : similarDocs) {
                    Object factIdObj = doc.getMetadata().get("factId");
                    if (factIdObj == null) continue;

                    Long similarFactId = Long.parseLong(factIdObj.toString());
                    if (similarFactId.equals(fact.getId()) || removedIds.contains(similarFactId)) continue;

                    double similarity = resolveDocSimilarity(fact.getFactText(), doc);
                    log.debug("[Memory] consolidate check | similarity={}", String.format("%.4f", similarity));

                    if (similarity >= DEDUP_THRESHOLD_CONSOLIDATE) {
                        Optional<UserMemoryFact> similarOpt = factRepository.findById(similarFactId);
                        if (similarOpt.isPresent() && similarOpt.get().getIsActive()) {
                            UserMemoryFact toRemove = fact.getImportance() >= similarOpt.get().getImportance()
                                    ? similarOpt.get() : fact;
                            deactivateFact(toRemove);
                            removedIds.add(toRemove.getId());
                            removed++;
                            log.debug("[Memory] removed duplicate | id={}", toRemove.getId());
                            if (toRemove.getId().equals(fact.getId())) break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Memory] consolidate check failed | factId={}, error={}", fact.getId(), e.getMessage());
            }
        }
        return removed;
    }

    private void deactivateFact(UserMemoryFact fact) {
        fact.setIsActive(false);
        factRepository.save(fact);
        try {
            semanticMemoryService.deleteFactDocument(fact.getId());
        } catch (Exception e) {
            log.warn("[Memory] delete vector failed | factId={}", fact.getId());
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private Optional<UserMemoryFact> findSemanticDuplicate(String userId, String text, double threshold) {
        try {
            List<Document> similarDocs = semanticMemoryService.searchRelevantFacts(userId, text, 5);
            for (Document doc : similarDocs) {
                if (resolveDocSimilarity(text, doc) >= threshold) {
                    Object factIdObj = doc.getMetadata().get("factId");
                    if (factIdObj != null) {
                        return factRepository.findById(Long.parseLong(factIdObj.toString()))
                                .filter(UserMemoryFact::getIsActive);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Memory] semantic dedup check failed | error={}", e.getMessage());
        }
        return Optional.empty();
    }

    private double resolveDocSimilarity(String text, Document doc) {
        Object scoreObj = doc.getMetadata().get("score");
        if (scoreObj instanceof Number num) {
            return num.doubleValue();
        }
        return calculateTextSimilarity(text, doc.getText());
    }

    private double calculateTextSimilarity(String text1, String text2) {
        if (text1.equals(text2)) return 1.0;

        Set<String> bigrams1 = extractCharBigrams(text1);
        Set<String> bigrams2 = extractCharBigrams(text2);
        Set<String> intersection = new HashSet<>(bigrams1);
        intersection.retainAll(bigrams2);
        Set<String> union = new HashSet<>(bigrams1);
        union.addAll(bigrams2);
        double bigramJaccard = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();

        Set<Character> chars1 = new HashSet<>();
        for (char c : text1.toCharArray()) chars1.add(c);
        Set<Character> chars2 = new HashSet<>();
        for (char c : text2.toCharArray()) chars2.add(c);
        Set<Character> charInter = new HashSet<>(chars1);
        charInter.retainAll(chars2);
        Set<Character> charUnion = new HashSet<>(chars1);
        charUnion.addAll(chars2);
        double charJaccard = charUnion.isEmpty() ? 0.0 : (double) charInter.size() / charUnion.size();

        int maxLen = Math.max(text1.length(), text2.length());
        double editSimilarity = maxLen == 0 ? 1.0 : 1.0 - ((double) editDistance(text1, text2) / maxLen);

        return bigramJaccard * 0.5 + charJaccard * 0.25 + editSimilarity * 0.25;
    }

    private Set<String> extractCharBigrams(String text) {
        Set<String> bigrams = new HashSet<>();
        String cleaned = text.replaceAll("[\\s\\p{Punct}，。、；：！？（）【】《》\"']", "");
        for (int i = 0; i < cleaned.length() - 1; i++) {
            bigrams.add(cleaned.substring(i, i + 2));
        }
        if (cleaned.length() == 1) bigrams.add(cleaned);
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
                curr[j] = s1.charAt(i - 1) == s2.charAt(j - 1)
                        ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            prev = curr;
        }
        return prev[n];
    }

    private String buildConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof UserMessage u) sb.append("User: ").append(u.getText()).append("\n");
            else if (msg instanceof AssistantMessage a) sb.append("Assistant: ").append(a.getText()).append("\n");
        }
        return sb.toString();
    }

    private MemoryExtractionLog buildExtractionLog(String conversationId, String userId, int msgCount) {
        MemoryExtractionLog log = new MemoryExtractionLog();
        log.setConversationId(conversationId);
        log.setUserId(userId);
        log.setExtractionType("fact");
        log.setInputMessageCount(msgCount);
        return log;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object obj) {
        return (List<Map<String, Object>>) obj;
    }
}
