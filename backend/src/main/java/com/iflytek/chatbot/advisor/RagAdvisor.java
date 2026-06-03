package com.iflytek.chatbot.advisor;

import com.iflytek.chatbot.service.RagService;
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
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 知识检索 Advisor（order=3）
 *
 * <p>在每次对话请求前，从 RAG 知识库语义检索与当前消息相关的文档片段，
 * 追加到 SystemMessage 中，使大模型能基于用户上传的知识文档回答问题。</p>
 */
@Component
public class RagAdvisor implements BaseChatMemoryAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RagAdvisor.class);

    private final RagService ragService;

    public RagAdvisor(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userId = (String) request.context().get("chat_memory_user_id");
        if (userId == null || userId.isBlank()) {
            log.debug("[Advisor-3] 无 userId, 跳过 RAG 检索");
            return request;
        }

        String userMessage = extractUserMessage(request);
        if (userMessage == null || userMessage.isBlank()) {
            log.debug("[Advisor-3] 无用户消息, 跳过 RAG 检索");
            return request;
        }

        log.info("[Advisor-3] >>> 开始 RAG 检索 | userId={}, query={}",
                userId, userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage);

        try {
            List<Document> chunks = ragService.searchRelevantChunks(userId, userMessage, 5);

            if (chunks.isEmpty()) {
                log.info("[Advisor-3] <<< 未检索到相关知识文档, 跳过注入");
                return request;
            }

            String ragContext = chunks.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.info("[Advisor-3] <<< 检索到 {} 个相关文本块, 注入 SystemMessage", chunks.size());

            // 追加到 SystemMessage
            Prompt originalPrompt = request.prompt();
            List<Message> messages = new ArrayList<>(originalPrompt.getInstructions());

            boolean hasSystem = false;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i) instanceof SystemMessage sysMsg) {
                    messages.set(i, new SystemMessage(
                            sysMsg.getText() + "\n\nRelevant Knowledge:\n" + ragContext));
                    hasSystem = true;
                    break;
                }
            }

            if (!hasSystem) {
                messages.add(0, new SystemMessage("Relevant Knowledge:\n" + ragContext));
            }

            Prompt newPrompt = new Prompt(messages, originalPrompt.getOptions());
            return request.mutate().prompt(newPrompt).build();

        } catch (Exception e) {
            log.error("[Advisor-3] RAG 检索异常: {}", e.getMessage(), e);
            return request;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 3;
    }

    private String extractUserMessage(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }
}
