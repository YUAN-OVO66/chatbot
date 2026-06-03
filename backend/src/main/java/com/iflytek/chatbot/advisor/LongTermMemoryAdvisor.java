package com.iflytek.chatbot.advisor;

import com.iflytek.chatbot.entity.UserMemoryFact;
import com.iflytek.chatbot.service.LongTermMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆 Advisor（order=2）
 *
 * <p>在每次对话请求前，从 Milvus 向量库语义检索与当前消息相关的用户事实/偏好，
 * 追加到 SystemMessage 中，使大模型能"记住"用户的历史信息。</p>
 */
@Component
public class LongTermMemoryAdvisor implements BaseChatMemoryAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryAdvisor.class);

    private final LongTermMemoryService memoryService;

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userId = (String) request.context().get("chat_memory_user_id");
        if (userId == null || userId.isBlank()) {
            log.debug("[Advisor-2] 无 userId, 跳过长期记忆检索");
            return request;
        }

        String userMessage = extractUserMessage(request);
        if (userMessage == null || userMessage.isBlank()) {
            log.debug("[Advisor-2] 无用户消息, 跳过长期记忆检索");
            return request;
        }

        log.info("[Advisor-2] >>> 开始长期记忆检索 | userId={}, query={}", userId,
                userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage);

        try {
            List<UserMemoryFact> facts = memoryService.retrieveRelevantFacts(userId, userMessage, 5);

            if (facts.isEmpty()) {
                log.info("[Advisor-2] <<< 未检索到相关事实, 跳过注入");
                return request;
            }

            String memoryContext = facts.stream()
                    .map(f -> "- [" + f.getCategory() + "] " + f.getFactText())
                    .collect(Collectors.joining("\n"));

            log.info("[Advisor-2] <<< 检索到 {} 条相关事实, 注入 SystemMessage:\n{}",
                    facts.size(), memoryContext);

            // 追加到 SystemMessage
            Prompt originalPrompt = request.prompt();
            List<Message> messages = new java.util.ArrayList<>(originalPrompt.getInstructions());

            boolean hasSystem = false;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof SystemMessage sysMsg) {
                    messages.set(i, new SystemMessage(
                            sysMsg.getText() + "\n\nUser Memory:\n" + memoryContext));
                    hasSystem = true;
                    break;
                }
            }

            if (!hasSystem) {
                messages.add(0, new SystemMessage("User Memory:\n" + memoryContext));
            }

            Prompt newPrompt = new Prompt(messages, originalPrompt.getOptions());
            return request.mutate().prompt(newPrompt).build();

        } catch (Exception e) {
            log.error("[Advisor-2] 长期记忆检索异常: {}", e.getMessage(), e);
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 2;
    }

    private String extractUserMessage(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof org.springframework.ai.chat.messages.UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }
}
