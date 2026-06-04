package com.iflytek.chatbot.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件配置属性
 *
 * <p>对应 application.yml 中的 chatbot.plugins 配置段。</p>
 */
@ConfigurationProperties(prefix = "chatbot.plugins")
public class PluginConfigProperties {

    /** 插件系统总开关 */
    private boolean enabled = true;

    /** 各插件配置 */
    private Map<String, ItemConfig> items = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, ItemConfig> getItems() {
        return items;
    }

    public void setItems(Map<String, ItemConfig> items) {
        this.items = items;
    }

    /**
     * 判断指定插件是否启用（默认关闭，需显式启用）
     */
    public boolean isPluginEnabled(String pluginName) {
        if (!enabled) return false;
        ItemConfig item = items.get(pluginName);
        return item != null && item.isEnabled();
    }

    /**
     * 获取插件的自定义配置
     */
    public Map<String, String> getPluginConfig(String pluginName) {
        ItemConfig item = items.get(pluginName);
        return item != null ? item.getConfig() : Map.of();
    }

    public static class ItemConfig {
        private boolean enabled = true;
        private Map<String, String> config = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getConfig() {
            return config;
        }

        public void setConfig(Map<String, String> config) {
            this.config = config;
        }
    }
}
