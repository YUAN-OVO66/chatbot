package com.iflytek.chatbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j (OpenAPI) 接口文档配置
 * 访问地址: http://localhost:8080/doc.html
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chatbot Long-Term Memory API")
                        .version("1.0.0")
                        .description("聊天机器人长期记忆模块接口文档，包含聊天、会话管理、记忆管理等接口")
                        .contact(new Contact()
                                .name("iflytek")
                                .email("dev@iflytek.com")));
    }
}
