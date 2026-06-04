package com.iflytek.chatbot.controller;

import com.iflytek.chatbot.dto.Result;
import com.iflytek.chatbot.plugin.PluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 插件管理 API
 */
@RestController
@RequestMapping("/api/plugins")
@Tag(name = "插件管理", description = "查看和管理确定性插件")
public class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping
    @Operation(summary = "列出所有插件")
    public Result<List<PluginService.PluginInfo>> listPlugins() {
        return Result.success(pluginService.getAllPlugins());
    }

    @PutMapping("/{name}/enable")
    @Operation(summary = "启用插件")
    public Result<Void> enablePlugin(
            @Parameter(description = "插件名称") @PathVariable String name) {
        pluginService.setPluginEnabled(name, true);
        return Result.success();
    }

    @PutMapping("/{name}/disable")
    @Operation(summary = "禁用插件")
    public Result<Void> disablePlugin(
            @Parameter(description = "插件名称") @PathVariable String name) {
        pluginService.setPluginEnabled(name, false);
        return Result.success();
    }
}
