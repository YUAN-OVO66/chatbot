package com.iflytek.chatbot.controller;

import com.iflytek.chatbot.dto.*;
import com.iflytek.chatbot.entity.UserMemoryFact;
import com.iflytek.chatbot.entity.UserPreference;
import com.iflytek.chatbot.repository.UserMemoryFactRepository;
import com.iflytek.chatbot.repository.UserPreferenceRepository;
import com.iflytek.chatbot.service.LongTermMemoryService;
import com.iflytek.chatbot.service.SemanticMemoryService;
import org.springframework.ai.chat.memory.ChatMemory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/memory")
@Tag(name = "记忆管理", description = "查看、创建、编辑、删除长期记忆事实与用户偏好")
public class MemoryController {

    private final UserMemoryFactRepository factRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final LongTermMemoryService longTermMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ChatMemory chatMemory;

    public MemoryController(UserMemoryFactRepository factRepository,
                             UserPreferenceRepository preferenceRepository,
                             LongTermMemoryService longTermMemoryService,
                             SemanticMemoryService semanticMemoryService,
                             ChatMemory chatMemory) {
        this.factRepository = factRepository;
        this.preferenceRepository = preferenceRepository;
        this.longTermMemoryService = longTermMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.chatMemory = chatMemory;
    }

    // ======================== 记忆事实 ========================

    @GetMapping("/facts")
    @Operation(summary = "记忆事实列表", description = "获取用户的长期记忆事实，支持按分类筛选，按重要性降序排列")
    public Result<List<MemoryFactResponse>> listFacts(
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "分类筛选（可选）") @RequestParam(required = false) String category) {

        List<UserMemoryFact> facts;
        if (category != null && !category.isBlank()) {
            facts = factRepository.findByUserIdAndCategoryAndIsActiveOrderByImportanceDesc(userId, category, true);
        } else {
            facts = factRepository.findByUserIdAndIsActiveOrderByImportanceDescCreatedAtDesc(userId, true);
        }

        List<MemoryFactResponse> response = facts.stream().map(f -> new MemoryFactResponse(
                f.getId(), f.getUserId(), f.getFactText(), f.getCategory(),
                f.getImportance(), f.getCreatedAt(), f.getUpdatedAt()
        )).toList();

        return Result.success(response);
    }

    @PostMapping("/facts")
    @Operation(summary = "手动创建事实", description = "手动创建一条记忆事实，含语义去重，会自动向量化存储到 Milvus")
    public Result<MemoryFactResponse> createFact(@RequestBody FactCreateRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            return Result.error("userId 不能为空");
        }
        if (request.factText() == null || request.factText().isBlank()) {
            return Result.error("factText 不能为空");
        }

        UserMemoryFact fact = longTermMemoryService.createFact(
                request.userId(), request.factText(), request.category(), request.importance());

        return Result.success(new MemoryFactResponse(
                fact.getId(), fact.getUserId(), fact.getFactText(), fact.getCategory(),
                fact.getImportance(), fact.getCreatedAt(), fact.getUpdatedAt()));
    }

    @PutMapping("/facts/{factId}")
    @Operation(summary = "编辑事实", description = "编辑一条记忆事实的文本、分类或重要性，文本变更时会重新向量化")
    public Result<MemoryFactResponse> updateFact(
            @PathVariable Long factId,
            @RequestBody FactCreateRequest request) {

        UserMemoryFact fact = longTermMemoryService.updateFact(
                factId, request.factText(), request.category(), request.importance());

        return Result.success(new MemoryFactResponse(
                fact.getId(), fact.getUserId(), fact.getFactText(), fact.getCategory(),
                fact.getImportance(), fact.getCreatedAt(), fact.getUpdatedAt()));
    }

    @DeleteMapping("/facts/{factId}")
    @Operation(summary = "删除记忆事实", description = "软删除一条记忆事实，同时删除 Milvus 中的向量")
    public Result<Void> deleteFact(@PathVariable Long factId) {
        factRepository.findById(factId).ifPresent(fact -> {
            fact.setIsActive(false);
            factRepository.save(fact);
            try {
                semanticMemoryService.deleteFactDocument(factId);
            } catch (Exception e) {
                // 向量删除失败不影响 MySQL 软删除
            }
        });
        return Result.success();
    }

    // ======================== 用户偏好 ========================

    @GetMapping("/preferences")
    @Operation(summary = "偏好列表", description = "获取指定用户的所有偏好设置，按置信度降序排列")
    public Result<List<PreferenceResponse>> listPreferences(@RequestParam String userId) {
        List<PreferenceResponse> response = preferenceRepository.findByUserIdOrderByConfidenceDesc(userId)
                .stream().map(p -> new PreferenceResponse(
                        p.getId(), p.getUserId(), p.getPreferenceKey(), p.getPreferenceValue(),
                        p.getConfidence(), p.getSource(), p.getCreatedAt(), p.getUpdatedAt()
                )).toList();
        return Result.success(response);
    }

    @PutMapping("/preferences")
    @Operation(summary = "设置偏好", description = "手动设置或更新用户偏好，来源标记为explicit，置信度为1.0")
    public Result<PreferenceResponse> setPreference(@RequestBody PreferenceRequest request) {
        UserPreference pref = preferenceRepository
                .findByUserIdAndPreferenceKey(request.userId(), request.preferenceKey())
                .orElse(new UserPreference());
        pref.setUserId(request.userId());
        pref.setPreferenceKey(request.preferenceKey());
        pref.setPreferenceValue(request.preferenceValue());
        pref.setConfidence(new BigDecimal("1.00"));
        pref.setSource("explicit");
        pref = preferenceRepository.save(pref);

        return Result.success(new PreferenceResponse(
                pref.getId(), pref.getUserId(), pref.getPreferenceKey(), pref.getPreferenceValue(),
                pref.getConfidence(), pref.getSource(), pref.getCreatedAt(), pref.getUpdatedAt()));
    }

    @DeleteMapping("/preferences/{preferenceKey}")
    @Operation(summary = "删除偏好", description = "删除指定用户的一条偏好设置")
    public Result<Void> deletePreference(
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "偏好键") @PathVariable String preferenceKey) {
        preferenceRepository.deleteByUserIdAndPreferenceKey(userId, preferenceKey);
        return Result.success();
    }

    // ======================== 记忆操作 ========================

    @PostMapping("/consolidate")
    @Operation(summary = "整合记忆", description = "触发记忆整合，去除重复和矛盾的记忆事实")
    public Result<Void> consolidate(@RequestParam String userId) {
        longTermMemoryService.consolidateMemories(userId);
        return Result.success();
    }

    @DeleteMapping("/reset")
    @Operation(summary = "重置所有记忆", description = "清空指定用户的所有记忆事实、偏好和向量数据，此操作不可恢复")
    public Result<Void> resetAllMemory(
            @Parameter(description = "用户ID") @RequestParam String userId) {
        longTermMemoryService.resetAllMemory(userId);
        return Result.success();
    }

    @PostMapping("/extract/{sessionId}")
    @Operation(summary = "手动触发提取", description = "手动触发指定会话的事实提取，从对话历史中提取用户事实和偏好")
    public Result<String> extractFacts(
            @PathVariable String sessionId,
            @Parameter(description = "用户ID") @RequestParam String userId) {

        List<Message> messages = chatMemory.get(sessionId);
        if (messages.isEmpty()) {
            return Result.error("会话不存在或无历史消息: " + sessionId);
        }

        longTermMemoryService.extractFacts(sessionId, userId, messages);
        return Result.success("提取完成，会话消息数: " + messages.size());
    }

    @GetMapping("/stats")
    @Operation(summary = "记忆统计", description = "获取用户的记忆事实总数和各分类数量")
    public Result<MemoryStatsResponse> getStats(@RequestParam String userId) {
        long totalFacts = factRepository.countByUserIdAndIsActive(userId, true);
        long totalPreferences = preferenceRepository.findByUserIdOrderByConfidenceDesc(userId).size();

        return Result.success(new MemoryStatsResponse(totalFacts, totalPreferences));
    }

    private record MemoryStatsResponse(long totalFacts, long totalPreferences) {}
}
