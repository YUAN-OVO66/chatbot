package com.iflytek.chatbot.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文注入 Advisor 的通用模板：取 userId / 用户消息 → 调用子类的 {@link #retrieveContext}
 * → 把返回的文本拼到 SystemMessage 头部。
 */
public abstract class AbstractContextInjectingAdvisor implements BaseChatMemoryAdvisor {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userId = (String) request.context().get("chat_memory_user_id");
        if (userId == null || userId.isBlank()) {
            log.debug("[{}] 无 userId, 跳过检索", getClass().getSimpleName());
            return request;
        }

        String userMessage = extractUserMessage(request);
        if (userMessage == null || userMessage.isBlank()) {
            log.debug("[{}] 无用户消息, 跳过检索", getClass().getSimpleName());
            return request;
        }

        try {
            String contextText = retrieveContext(userId, userMessage);
            if (contextText == null || contextText.isBlank()) {
                return request;
            }

            String header = headerLabel() + ":\n" + contextText;
            Prompt originalPrompt = request.prompt();
            List<Message> messages = new ArrayList<>(originalPrompt.getInstructions());

            boolean hasSystem = false;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof SystemMessage sysMsg) {
                    messages.set(i, new SystemMessage(sysMsg.getText() + "\n\n" + header));
                    hasSystem = true;
                    break;
                }
            }
            if (!hasSystem) {
                messages.add(0, new SystemMessage(header));
            }

            Prompt newPrompt = new Prompt(messages, originalPrompt.getOptions());
            return request.mutate().prompt(newPrompt).build();
        } catch (Exception e) {
            log.error("[{}] 上下文注入异常: {}", getClass().getSimpleName(), e.getMessage(), e);
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /** 子类实现：根据 userId + 用户消息检索拼好的上下文文本，返回 null/空白则不注入。 */
    protected abstract String retrieveContext(String userId, String userMessage);

    /** 注入文本的标题，例如 "User Memory" / "Relevant Knowledge"。 */
    protected abstract String headerLabel();

    private static String extractUserMessage(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }
}
