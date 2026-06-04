package com.iflytek.chatbot.controller;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.iflytek.chatbot.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Skill 管理 API
 */
@RestController
@RequestMapping("/api/skills")
@Tag(name = "技能管理", description = "查看和管理 LLM 可用的技能")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @GetMapping
    @Operation(summary = "列出所有已注册的技能")
    public Result<List<SkillMetadata>> listSkills() {
        return Result.success(skillRegistry.listAll());
    }

    @GetMapping("/{name}")
    @Operation(summary = "获取指定技能详情")
    public Result<SkillMetadata> getSkill(@PathVariable String name) {
        return skillRegistry.get(name)
                .map(Result::success)
                .orElse(Result.error("技能不存在: " + name));
    }

    @GetMapping("/{name}/content")
    @Operation(summary = "读取技能的 SKILL.md 内容")
    public Result<String> readSkillContent(@PathVariable String name) {
        if (!skillRegistry.contains(name)) {
            return Result.error("技能不存在: " + name);
        }
        try {
            String content = skillRegistry.readSkillContent(name);
            return Result.success(content);
        } catch (Exception e) {
            log.error("[SkillController] 读取技能内容失败 | name={}", name, e);
            return Result.error("读取失败: " + e.getMessage());
        }
    }

    @PostMapping("/reload")
    @Operation(summary = "重新加载技能")
    public Result<String> reload() {
        try {
            skillRegistry.reload();
            return Result.success("重载完成，当前技能数: " + skillRegistry.size());
        } catch (Exception e) {
            log.error("[SkillController] 重载技能失败", e);
            return Result.error("重载失败: " + e.getMessage());
        }
    }
}
