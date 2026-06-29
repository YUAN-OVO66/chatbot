package com.iflytek.chatbot.config;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.iflytek.chatbot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(SkillConfigProperties.class)
public class SkillConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillConfig.class);

    private final ConfigurableEnvironment environment;

    public SkillConfig(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Bean
    public SkillRegistry skillRegistry(SkillConfigProperties properties) {
        String directory = properties.getDirectory();
        log.info("[SkillConfig] 创建 SkillRegistry | directory={}", directory);

        String classpathPath = directory.startsWith("classpath:")
                ? directory.substring("classpath:".length())
                : directory;

        SkillRegistry registry = ClasspathSkillRegistry.builder()
                .classpathPath(classpathPath)
                .autoLoad(true)
                .build();

        log.info("[SkillConfig] SkillRegistry 已创建 | type={}, skills={}",
                registry.getRegistryType(), registry.size());
        return registry;
    }

    @Bean
    public SkillPromptAugmentAdvisor skillPromptAugmentAdvisor(SkillRegistry skillRegistry) {
        SkillPromptAugmentAdvisor advisor = SkillPromptAugmentAdvisor.builder()
                .skillRegistry(skillRegistry)
                .order(5)
                .build();

        log.info("[SkillConfig] SkillPromptAugmentAdvisor 已创建 | order=5, skills={}",
                skillRegistry.size());
        return advisor;
    }

    @Bean
    public ToolCallback readSkillToolCallback(SkillRegistry skillRegistry) {
        ToolCallback callback = ReadSkillTool.createReadSkillToolCallback(
                skillRegistry, ReadSkillTool.READ_SKILL);
        log.info("[SkillConfig] ReadSkillTool 已创建 | name={}", ReadSkillTool.READ_SKILL);
        return callback;
    }

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @Bean
    public ToolCallback shellToolCallback(SkillConfigProperties properties) {
        String example = IS_WINDOWS
                ? "python C:/tmp/skills/weather/scripts/get_weather.py 北京"
                : "python3 /tmp/skills/weather/scripts/get_weather.py 北京";

        java.util.Set<String> allowedEnv = new java.util.LinkedHashSet<>(properties.getPassthroughEnv());

        ToolCallback callback = FunctionToolCallback.builder(
                        "shell",
                        (ShellRequest request) -> executeShell(request.command(), allowedEnv))
                .description("Execute a command. On this system use 'python' (not 'python3'). " +
                        "Example: " + example)
                .inputType(ShellRequest.class)
                .build();
        log.info("[SkillConfig] ShellToolCallback 已创建 | os={}, passthroughEnv={}",
                IS_WINDOWS ? "Windows" : "Unix", allowedEnv);
        return callback;
    }

    private String executeShell(String command, java.util.Set<String> allowedEnv) {
        log.info("[Shell] 原始命令: {}", command);

        if (IS_WINDOWS) {
            command = WindowsCommandNormalizer.normalize(command);
        }

        log.info("[Shell] 执行命令: {}", command);
        try {
            // 直接用 ProcessBuilder 执行，不经过 bash/cmd 包装
            // Windows 上 ProcessBuilder 会自动查找 python.exe
            ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            // 仅把白名单内的 .env 变量注入子进程，避免 DB/LLM 密钥默认外泄给 LLM 控制的脚本
            if (!allowedEnv.isEmpty()) {
                for (PropertySource<?> ps : environment.getPropertySources()) {
                    if (ps.getName().equals("dotenv") && ps.getSource() instanceof java.util.Properties props) {
                        for (String key : allowedEnv) {
                            String value = props.getProperty(key);
                            if (value != null) {
                                pb.environment().put(key, value);
                            }
                        }
                        break;
                    }
                }
            }

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return "Error: 命令执行超时（30秒）";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "Error (exit=" + exitCode + "): " + output;
            }

            String result = output.isBlank() ? "<no output>" : output.trim();
            log.info("[Shell] 执行成功 | output={}", result.length() > 200
                    ? result.substring(0, 200) + "..." : result);
            return result;
        } catch (Exception e) {
            log.error("[Shell] 执行异常: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    public record ShellRequest(String command) {}
}
