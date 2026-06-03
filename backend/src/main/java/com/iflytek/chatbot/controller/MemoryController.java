package com.iflytek.chatbot.controller;

import com.iflytek.chatbot.dto.PreferenceRequest;
import com.iflytek.chatbot.dto.Result;
import com.iflytek.chatbot.entity.UserMemoryFact;
import com.iflytek.chatbot.entity.UserPreference;
import com.iflytek.chatbot.repository.UserMemoryFactRepository;
import com.iflytek.chatbot.repository.UserPreferenceRepository;
import com.iflytek.chatbot.service.LongTermMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/memory")
@Tag(name = "记忆管理", description = "查看、删除长期记忆事实，管理用户偏好")
public class MemoryController {

    private final UserMemoryFactRepository factRepository;
    private final UserPreferenceRepository preferenceRepository;
    private final LongTermMemoryService longTermMemoryService;

    public MemoryController(UserMemoryFactRepository factRepository,
                             UserPreferenceRepository preferenceRepository,
                             LongTermMemoryService longTermMemoryService) {
        this.factRepository = factRepository;
        this.preferenceRepository = preferenceRepository;
        this.longTermMemoryService = longTermMemoryService;
    }

    /** 查询用户的所有长期记忆事实 */
    @GetMapping("/facts")
    @Operation(summary = "记忆事实列表", description = "获取指定用户的所有长期记忆事实，按重要性降序排列")
    public Result<List<UserMemoryFact>> listFacts(@RequestParam String userId) {
        return Result.success(
                factRepository.findByUserIdAndIsActiveOrderByImportanceDescCreatedAtDesc(userId, true));
    }

    /** 删除一条记忆事实（软删除） */
    @DeleteMapping("/facts/{factId}")
    @Operation(summary = "删除记忆事实", description = "软删除一条记忆事实，不会从数据库物理删除")
    public Result<Void> deleteFact(@PathVariable Long factId) {
        factRepository.findById(factId).ifPresent(fact -> {
            fact.setIsActive(false);
            factRepository.save(fact);
        });
        return Result.success();
    }

    /** 查询用户的所有偏好设置 */
    @GetMapping("/preferences")
    @Operation(summary = "偏好列表", description = "获取指定用户的所有偏好设置，按置信度降序排列")
    public Result<List<UserPreference>> listPreferences(@RequestParam String userId) {
        return Result.success(preferenceRepository.findByUserIdOrderByConfidenceDesc(userId));
    }

    /** 手动设置用户偏好（显式声明，置信度最高） */
    @PutMapping("/preferences")
    @Operation(summary = "设置偏好", description = "手动设置或更新用户偏好，来源标记为explicit，置信度为1.0")
    public Result<UserPreference> setPreference(@RequestBody PreferenceRequest request) {
        UserPreference pref = preferenceRepository
                .findByUserIdAndPreferenceKey(request.userId(), request.preferenceKey())
                .orElse(new UserPreference());
        pref.setUserId(request.userId());
        pref.setPreferenceKey(request.preferenceKey());
        pref.setPreferenceValue(request.preferenceValue());
        pref.setConfidence(new BigDecimal("1.00"));
        pref.setSource("explicit");
        return Result.success(preferenceRepository.save(pref));
    }

    /** 手动触发记忆整合（去重） */
    @PostMapping("/consolidate")
    @Operation(summary = "整合记忆", description = "触发记忆整合，去除重复和矛盾的记忆事实")
    public Result<Void> consolidate(@RequestParam String userId) {
        longTermMemoryService.consolidateMemories(userId);
        return Result.success();
    }
}
