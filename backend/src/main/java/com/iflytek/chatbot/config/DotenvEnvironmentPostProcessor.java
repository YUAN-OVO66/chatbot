package com.iflytek.chatbot.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * 在 Spring 环境初始化阶段加载 .env 文件中的环境变量
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Properties props = new Properties();

        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(".")
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();
            dotenv.entries().forEach(entry -> props.setProperty(entry.getKey(), entry.getValue()));
        } catch (DotenvException ignored) {
        }

        if (props.isEmpty()) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(".env")) {
                if (is != null) {
                    new BufferedReader(new InputStreamReader(is)).lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .filter(line -> line.contains("="))
                            .forEach(line -> {
                                int idx = line.indexOf('=');
                                String key = line.substring(0, idx).trim();
                                String value = line.substring(idx + 1).trim();
                                props.setProperty(key, value);
                            });
                }
            } catch (Exception ignored) {
            }
        }

        if (!props.isEmpty()) {
            environment.getPropertySources().addLast(new PropertiesPropertySource("dotenv", props));
        }
    }
}
