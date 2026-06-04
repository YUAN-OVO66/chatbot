package com.iflytek.chatbot.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 配置属性
 */
@ConfigurationProperties(prefix = "chatbot.skills")
public class SkillConfigProperties {

    /** 技能目录（classpath:skills 或文件系统路径） */
    private String directory = "classpath:skills";

    /** 是否自动重载技能 */
    private boolean autoReload = true;

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public boolean isAutoReload() {
        return autoReload;
    }

    public void setAutoReload(boolean autoReload) {
        this.autoReload = autoReload;
    }
}
