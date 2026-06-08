package com.iflytek.chatbot.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件执行引擎
 *
 * <p>负责注册、过滤、执行所有 ChatPlugin。
 * 在 ChatService 中以 beforeRag / afterRag 方式包裹 LLM 调用。</p>
 */
@Service
public class PluginService {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    private final List<ChatPlugin> allPlugins;
    private final PluginConfigProperties config;
    private final Map<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();

    public PluginService(List<ChatPlugin> allPlugins, PluginConfigProperties config) {
        this.allPlugins = allPlugins;
        this.config = config;
        this.allPlugins.sort(Comparator.comparingInt(ChatPlugin::getOrder));
        log.info("[PluginService] 已注册 {} 个插件: {}",
                allPlugins.size(), allPlugins.stream().map(ChatPlugin::getName).toList());
    }

    /**
     * 执行 beforeRag 阶段（按 order 升序）
     *
     * @return 聚合结果：CONTINUE / SHORT_CIRCUIT / MODIFIED_QUERY
     */
    public BeforeRagResult executeBeforeRag(String query, String userId) {
        String actualQuery = query;

        for (ChatPlugin plugin : allPlugins) {
            if (!isPluginEnabled(plugin.getName())) {
                continue;
            }

            log.debug("[PluginService] beforeRag | plugin={}, query={}", plugin.getName(), query);
            PluginResult result = plugin.beforeRag(actualQuery, userId);

            switch (result.action()) {
                case SHORT_CIRCUIT -> {
                    log.info("[PluginService] 插件短路 | plugin={}, answer={}",
                            plugin.getName(),
                            result.answer().length() > 100
                                    ? result.answer().substring(0, 100) + "..."
                                    : result.answer());
                    return BeforeRagResult.shortCircuit(result.answer(), plugin.getName());
                }
                case MODIFIED_QUERY -> {
                    log.info("[PluginService] 查询已修改 | plugin={}, newQuery={}",
                            plugin.getName(), result.modifiedQuery());
                    actualQuery = result.modifiedQuery();
                }
                case CONTINUE -> {
                    // 继续下一个插件
                }
            }
        }

        return BeforeRagResult.continueWith(actualQuery);
    }

    /**
     * 执行 afterRag 阶段（按 order 降序）
     */
    public String executeAfterRag(String answer, String query, String userId, PluginContext context) {
        String currentAnswer = answer;

        // afterRag 按 order 降序执行
        List<ChatPlugin> reversed = new ArrayList<>(allPlugins);
        java.util.Collections.reverse(reversed);
        for (ChatPlugin plugin : reversed) {
            if (!isPluginEnabled(plugin.getName())) {
                continue;
            }

            log.debug("[PluginService] afterRag | plugin={}", plugin.getName());
            try {
                String modified = plugin.afterRag(currentAnswer, query, userId, context);
                if (modified != null && !modified.equals(currentAnswer)) {
                    log.info("[PluginService] 回复已修改 | plugin={}", plugin.getName());
                    currentAnswer = modified;
                }
            } catch (Exception e) {
                log.error("[PluginService] afterRag 执行异常 | plugin={}, error={}",
                        plugin.getName(), e.getMessage(), e);
            }
        }

        return currentAnswer;
    }

    /**
     * 获取所有插件信息（用于 API 展示）
     */
    public List<PluginInfo> getAllPlugins() {
        return allPlugins.stream()
                .map(p -> new PluginInfo(
                        p.getName(),
                        p.getOrder(),
                        isPluginEnabled(p.getName())))
                .toList();
    }

    public boolean isPluginEnabled(String pluginName) {
        Boolean override = runtimeOverrides.get(pluginName);
        boolean configEnabled = config.isPluginEnabled(pluginName);
        boolean result = override != null ? override : configEnabled;
        log.info("[PluginService] isPluginEnabled | plugin={}, override={}, config={}, result={}",
                pluginName, override, configEnabled, result);
        return result;
    }

    public void setPluginEnabled(String pluginName, boolean enabled) {
        runtimeOverrides.put(pluginName, enabled);
        log.info("[PluginService] 插件状态变更 | plugin={}, enabled={}", pluginName, enabled);
    }

    /**
     * 插件信息（用于 API 返回）
     */
    public record PluginInfo(String name, int order, boolean enabled) {}

    /**
     * beforeRag 聚合结果
     */
    public record BeforeRagResult(
            boolean shortCircuit,
            String answer,
            String actualQuery,
            String shortCircuitPlugin
    ) {
        public static BeforeRagResult shortCircuit(String answer, String pluginName) {
            return new BeforeRagResult(true, answer, null, pluginName);
        }

        public static BeforeRagResult continueWith(String actualQuery) {
            return new BeforeRagResult(false, null, actualQuery, null);
        }
    }
}
