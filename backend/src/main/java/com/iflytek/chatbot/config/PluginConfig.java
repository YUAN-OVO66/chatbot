package com.iflytek.chatbot.config;

import com.iflytek.chatbot.plugin.PluginConfigProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 插件模块配置
 */
@Configuration
@EnableConfigurationProperties(PluginConfigProperties.class)
public class PluginConfig {
}
