package com.iflytek.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.chatbot.dto.ChatMessage;
import com.iflytek.chatbot.dto.ChatRequest;
import com.iflytek.chatbot.dto.ChatResponse;
import com.iflytek.chatbot.entity.ChatSession;
import com.iflytek.chatbot.plugin.PluginContext;
import com.iflytek.chatbot.plugin.PluginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final SessionService sessionService;
    private final PluginService pluginService;
    private final ObjectMapper objectMapper;
    private final AsyncPostProcessor asyncPostProcessor;
    private final ThreadPoolTaskExecutor chatTaskExecutor;
    private final JdbcTemplate jdbcTemplate;

    public ChatService(ChatClient chatClient,
                       ChatMemory chatMemory,
                       SessionService sessionService,
                       PluginService pluginService,
                       ObjectMapper objectMapper,
                       AsyncPostProcessor asyncPostProcessor,
                       @Qualifier("chatTaskExecutor") ThreadPoolTaskExecutor chatTaskExecutor,
                       JdbcTemplate jdbcTemplate) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.sessionService = sessionService;
        this.pluginService = pluginService;
        this.objectMapper = objectMapper;
        this.asyncPostProcessor = asyncPostProcessor;
        this.chatTaskExecutor = chatTaskExecutor;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ChatResponse chat(ChatRequest request) {
        // 1. 解析或创建会话
        String sessionId = resolveSessionId(request.userId(), request.sessionId());
        log.info("[ChatService] 会话已就绪 | sessionId={}, userId={}", sessionId, request.userId());

        // 2. 插件 beforeRag 阶段
        PluginService.BeforeRagResult beforeResult = pluginService.executeBeforeRag(
                request.message(), request.userId());

        String actualQuery = beforeResult.actualQuery();
        String reply;
        boolean shortCircuited = false;

        if (beforeResult.shortCircuit()) {
            // 插件短路：直接使用插件返回的答案，跳过 LLM
            reply = beforeResult.answer();
            shortCircuited = true;
            log.info("[ChatService] 插件短路 | plugin={}", beforeResult.shortCircuitPlugin());
        } else {
            // 3. 调用 ChatClient（四层 Advisor 链自动注入记忆上下文）
            log.info("[ChatService] >>> 调用 ChatClient（DeepSeek）开始 | message={}", actualQuery);
            long start = System.currentTimeMillis();

            reply = chatClient.prompt()
                    .user(actualQuery)
                    .advisors(a -> a
                            .param("chat_memory_conversation_id", sessionId)
                            .param("chat_memory_user_id", request.userId()))
                    .call()
                    .content();
            reply = Objects.toString(reply, "");

            long elapsed = System.currentTimeMillis() - start;
            String replyPreview = reply == null ? "(null)"
                    : reply.length() > 100 ? reply.substring(0, 100) + "..." : reply;
            log.info("[ChatService] <<< ChatClient 返回 | 耗时={}ms, reply={}", elapsed, replyPreview);

            // 如果插件修改了查询，将记忆中的用户消息还原为原始消息
            if (!actualQuery.equals(request.message())) {
                fixMemoryUserMessage(sessionId, request.message());
            }
        }

        // 4. 插件 afterRag 阶段
        PluginContext pluginCtx = new PluginContext(
                request.message(), actualQuery, shortCircuited,
                shortCircuited ? beforeResult.shortCircuitPlugin() : null);
        reply = Objects.toString(
                pluginService.executeAfterRag(reply, request.message(), request.userId(), pluginCtx),
                Objects.toString(reply, ""));

        // 5. 异步后处理
        log.info("[ChatService] 提交异步后处理 | sessionId={}", sessionId);
        asyncPostProcessor.extractAndStore(sessionId, request.userId(), request.message(), reply);

        return new ChatResponse(sessionId, reply);
    }

    private static final int MAX_STREAM_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 3000};

    public void streamChat(ChatRequest request, SseEmitter emitter) {
        emitter.onCompletion(() -> {});
        emitter.onTimeout(() -> emitter.complete());
        emitter.onError(t -> {});

        chatTaskExecutor.submit(() -> {
            try {
                // 1. 解析或创建会话
                String sessionId = resolveSessionId(request.userId(), request.sessionId());
                log.info("[ChatService-Stream] 会话已就绪 | sessionId={}", sessionId);

                // 2. 插件 beforeRag 阶段
                PluginService.BeforeRagResult beforeResult = pluginService.executeBeforeRag(
                        request.message(), request.userId());

                String actualQuery = beforeResult.actualQuery();

                if (beforeResult.shortCircuit()) {
                    String reply = beforeResult.answer();
                    log.info("[ChatService-Stream] 插件短路 | plugin={}", beforeResult.shortCircuitPlugin());

                    emitter.send(SseEmitter.event()
                            .name("delta")
                            .data(toJson(Map.of("content", reply))));
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data(toJson(Map.of("sessionId", sessionId, "reply", reply))));
                    emitter.complete();
                    return;
                }

                // 3. 带重试的流式调用
                log.info("[ChatService-Stream] >>> 开始流式调用 | message={}", actualQuery);
                long start = System.currentTimeMillis();
                final boolean[] chunkEmitted = {false}; // 一旦发出过 delta 就不再重试，避免客户端拼接重复内容

                for (int attempt = 0; attempt <= MAX_STREAM_RETRIES; attempt++) {
                    try {
                        if (attempt > 0) {
                            long delay = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
                            log.info("[ChatService-Stream] 第 {} 次重试, 等待 {}ms", attempt, delay);
                            Thread.sleep(delay);
                        }

                        StringBuilder fullReply = new StringBuilder();
                        Exception[] streamError = {null};

                        chatClient.prompt()
                                .user(actualQuery)
                                .advisors(a -> a
                                        .param("chat_memory_conversation_id", sessionId)
                                        .param("chat_memory_user_id", request.userId()))
                                .stream()
                                .content()
                                .doOnNext(chunk -> {
                                    fullReply.append(chunk);
                                    try {
                                        emitter.send(SseEmitter.event()
                                                .name("delta")
                                                .data(toJson(Map.of("content", chunk))));
                                        chunkEmitted[0] = true;
                                    } catch (Exception e) {
                                        log.warn("[ChatService-Stream] 发送 chunk 失败: {}", e.getMessage());
                                    }
                                })
                                .doOnError(error -> {
                                    streamError[0] = (error instanceof Exception ex) ? ex : new RuntimeException(error);
                                })
                                .blockLast();

                        if (streamError[0] != null) throw streamError[0];

                        long elapsed = System.currentTimeMillis() - start;
                        log.info("[ChatService-Stream] <<< 流式调用完成 | 耗时={}ms, 长度={}", elapsed, fullReply.length());

                        // 4. afterRag 阶段
                        String reply = fullReply.toString();
                        PluginContext pluginCtx = new PluginContext(
                                request.message(), actualQuery, false, null);
                        reply = Objects.toString(
                                pluginService.executeAfterRag(reply, request.message(), request.userId(), pluginCtx),
                                reply);

                        if (!actualQuery.equals(request.message())) {
                            fixMemoryUserMessage(sessionId, request.message());
                        }

                        // 5. 发送 done 事件
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data(toJson(Map.of("sessionId", sessionId, "reply", reply))));
                        emitter.complete();

                        // 6. 异步后处理
                        asyncPostProcessor.extractAndStore(sessionId, request.userId(), request.message(), reply);
                        return;

                    } catch (Exception e) {
                        boolean isConnectionError = isConnectionReset(e);
                        if (chunkEmitted[0]) {
                            log.warn("[ChatService-Stream] 已发出 delta, 放弃重试以避免重复内容 | attempt={}, error={}",
                                    attempt + 1, e.getMessage());
                            throw e;
                        }
                        if (isConnectionError && attempt < MAX_STREAM_RETRIES) {
                            log.warn("[ChatService-Stream] 连接异常, 将重试 | attempt={}, error={}", attempt + 1, e.getMessage());
                            continue;
                        }
                        throw e;
                    }
                }

            } catch (Exception e) {
                log.error("[ChatService-Stream] 最终失败: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(toJson(Map.of("message", String.valueOf(e.getMessage())))));
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
            }
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("[ChatService] JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private boolean isConnectionReset(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            // 优先判断异常类型，跨 OS 语言环境稳定
            if (cause instanceof java.net.SocketException
                    || cause instanceof java.io.EOFException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            // Netty / reactor-netty 在不同版本下的连接异常类（避免硬依赖，按类名匹配）
            String fqcn = cause.getClass().getName();
            if (fqcn.equals("io.netty.channel.StacklessClosedChannelException")
                    || fqcn.equals("io.netty.handler.timeout.ReadTimeoutException")
                    || fqcn.equals("reactor.netty.http.client.PrematureCloseException")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public String resolveSessionId(String userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            log.debug("[ChatService] 使用已有会话 | sessionId={}", sessionId);
            sessionService.getSession(sessionId);
            return sessionId;
        }
        ChatSession session = sessionService.createSession(userId);
        log.info("[ChatService] 自动创建新会话 | sessionId={}", session.getId());
        return session.getId();
    }

    /**
     * 获取指定会话的历史消息列表
     */
    public List<ChatMessage> getHistory(String sessionId) {
        log.info("[ChatService] 查询历史消息 | sessionId={}", sessionId);
        List<Message> messages = chatMemory.get(sessionId);
        log.info("[ChatService] 查询到 {} 条历史消息 | sessionId={}", messages.size(), sessionId);

        return messages.stream()
                .map(msg -> {
                    String role;
                    if (msg instanceof UserMessage) {
                        role = "user";
                    } else if (msg instanceof AssistantMessage) {
                        role = "assistant";
                    } else if (msg instanceof org.springframework.ai.chat.messages.SystemMessage) {
                        role = "system";
                    } else {
                        role = "other";
                    }
                    return new ChatMessage(role, msg.getText());
                })
                .toList();
    }

    /**
     * 修复记忆中的用户消息：当插件修改了查询（如搜索插件注入搜索结果）后，
     * MessageChatMemoryAdvisor 会将修改后的查询存入记忆。
     * 此方法将最后一条用户消息还原为用户的原始输入，避免刷新对话时显示搜索结果。
     */
    private void fixMemoryUserMessage(String sessionId, String originalMessage) {
        try {
            // 只 UPDATE 最近一条 USER 消息的 content，避免 saveAll 整段历史 delete-then-insert 的写放大
            int updated = jdbcTemplate.update(
                    "UPDATE SPRING_AI_CHAT_MEMORY SET content = ? " +
                            "WHERE conversation_id = ? AND type = 'USER' " +
                            "ORDER BY timestamp DESC LIMIT 1",
                    originalMessage, sessionId);

            if (updated > 0) {
                log.info("[ChatService] 已还原记忆中的用户消息为原始输入 | sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[ChatService] 还原记忆用户消息失败 | sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
