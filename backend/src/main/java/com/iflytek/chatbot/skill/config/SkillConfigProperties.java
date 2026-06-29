package com.iflytek.chatbot.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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

    /** Shell 工具配置 */
    private Shell shell = new Shell();

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

    public Shell getShell() {
        return shell;
    }

    public void setShell(Shell shell) {
        this.shell = shell == null ? new Shell() : shell;
    }

    public static class Shell {
        /** 是否启用 shell 工具。默认启用，生产环境可置 false 完全关闭执行能力。 */
        private boolean enabled = true;

        /** 单条命令执行超时 */
        private Duration timeout = Duration.ofSeconds(30);

        /** stdout / stderr 最大字节数，超过会被截断，防止脚本死循环刷爆日志 */
        private int maxOutputBytes = 8 * 1024;

        /** LLM 命令审查员配置 */
        private Review review = new Review();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }

        public int getMaxOutputBytes() {
            return maxOutputBytes;
        }

        public void setMaxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes <= 0 ? 8 * 1024 : maxOutputBytes;
        }

        public Review getReview() {
            return review;
        }

        public void setReview(Review review) {
            this.review = review == null ? new Review() : review;
        }
    }

    public static class Review {
        /** 是否启用 LLM 审查员（默认启用）。审查员只能在静态规则放行后做"二次否决"，不会扩大放行集。 */
        private boolean enabled = true;

        /** 审查员 LLM 调用超时；超时一律视为拒绝（fail-closed） */
        private Duration timeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }
    }
}
