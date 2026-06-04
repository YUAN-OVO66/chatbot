package com.iflytek.chatbot.config;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.iflytek.chatbot.advisor.LongTermMemoryAdvisor;
import com.iflytek.chatbot.advisor.RagAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心配置类：组装四层记忆体系
 *
 * <p>Advisor 执行顺序：</p>
 * <ol>
 *   <li>order=0 MessageChatMemoryAdvisor  —— 短期记忆：滑动窗口最近N条消息</li>
 *   <li>order=1 VectorStoreChatMemoryAdvisor —— 语义记忆：从Milvus检索相似历史对话</li>
 *   <li>order=2 LongTermMemoryAdvisor —— 长期记忆：检索用户事实/偏好</li>
 *   <li>order=3 RagAdvisor —— RAG知识检索：从用户知识库检索相关文档片段</li>
 *   <li>order=100 SimpleLoggerAdvisor —— 日志</li>
 * </ol>
 */
@Configuration
public class MemoryConfig {

    /**
     * 短期记忆：滑动窗口，保留最近30条消息
     * 底层由 JdbcChatMemoryRepository 持久化到 MySQL 的 SPRING_AI_CHAT_MEMORY 表
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(30)
                .build();
    }

    /**
     * ChatClient：所有聊天请求的入口
     * 注入 DeepSeek 大模型 + 三层 Advisor 链
     */
    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  ChatMemory chatMemory,
                                  VectorStore vectorStore,
                                  LongTermMemoryAdvisor longTermMemoryAdvisor,
                                  RagAdvisor ragAdvisor,
                                  SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                  ToolCallback readSkillToolCallback,
                                  ToolCallback shellToolCallback) {
        return ChatClient.builder(chatModel)
                .defaultSystem("You are a helpful assistant with long-term memory. " +
                        "Use the provided user memory and conversation context to give personalized responses.")
                .defaultAdvisors(
                        // 短期记忆：从MySQL读取最近对话消息注入prompt
                        MessageChatMemoryAdvisor.builder(chatMemory).order(0).build(),
                        // 语义记忆：从Milvus向量库检索相似对话注入prompt
                        VectorStoreChatMemoryAdvisor.builder(vectorStore)
                                .defaultTopK(5)
                                .order(1)
                                .build(),
                        // 长期记忆：从Milvus检索用户事实/偏好注入prompt
                        longTermMemoryAdvisor,
                        // RAG知识检索：从用户知识库检索相关文档片段注入prompt
                        ragAdvisor,
                        // Skill技能发现：注入技能列表到系统提示
                        skillPromptAugmentAdvisor,
                        new SimpleLoggerAdvisor()
                )
                // read_skill: 读取 SKILL.md | shell: 执行 Shell/Python 脚本
                .defaultToolCallbacks(readSkillToolCallback, shellToolCallback)
                .build();
    }
}
