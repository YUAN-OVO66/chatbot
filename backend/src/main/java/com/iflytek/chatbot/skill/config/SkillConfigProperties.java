package com.iflytek.chatbot.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;

/**
 * Skill 配置属性
 */
@ConfigurationProperties(prefix = "chatbot.skills")
public class SkillConfigProperties {

    /** 技能目录（classpath:skills 或文件系统路径） */
    private String directory = "classpath:skills";

    /** 是否自动重载技能 */
    private boolean autoReload = true;

    /**
     * 允许透传给 skill 子进程的环境变量白名单（默认空）。
     * 仅在此列表中的 key 才会从 .env 注入到子进程，避免 DB/LLM 密钥默认外泄。
     */
    private List<String> passthroughEnv = Collections.emptyList();

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

    public List<String> getPassthroughEnv() {
        return passthroughEnv;
    }

    public void setPassthroughEnv(List<String> passthroughEnv) {
        this.passthroughEnv = passthroughEnv == null ? Collections.emptyList() : passthroughEnv;
    }
}
