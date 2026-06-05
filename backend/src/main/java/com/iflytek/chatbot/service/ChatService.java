package com.iflytek.chatbot.service;

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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final SessionService sessionService;
    private final LongTermMemoryService longTermMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final PluginService pluginService;

    public ChatService(ChatClient chatClient,
                       ChatMemory chatMemory,
                       SessionService sessionService,
                       LongTermMemoryService longTermMemoryService,
                       SemanticMemoryService semanticMemoryService,
                       PluginService pluginService) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.sessionService = sessionService;
        this.longTermMemoryService = longTermMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.pluginService = pluginService;
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

            long elapsed = System.currentTimeMillis() - start;
            String replyPreview = reply.length() > 100 ? reply.substring(0, 100) + "..." : reply;
            log.info("[ChatService] <<< ChatClient 返回 | 耗时={}ms, reply={}", elapsed, replyPreview);
        }

        // 4. 插件 afterRag 阶段
        PluginContext pluginCtx = new PluginContext(
                request.message(), actualQuery, shortCircuited,
                shortCircuited ? beforeResult.shortCircuitPlugin() : null);
        reply = pluginService.executeAfterRag(reply, request.message(), request.userId(), pluginCtx);

        // 5. 异步后处理
        log.info("[ChatService] 提交异步后处理 | sessionId={}", sessionId);
        asyncExtractAndStore(sessionId, request.userId(), request.message(), reply);

        return new ChatResponse(sessionId, reply);
    }

    /** 记录每个 session 上次提取时的消息数，用于节流 */
    private final ConcurrentHashMap<String, Integer> lastExtractedSize = new ConcurrentHashMap<>();

    private static final int MAX_STREAM_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 3000};

    public void streamChat(ChatRequest request, SseEmitter emitter) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
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
                            .data("{\"content\": \"" + escapeJson(reply) + "\"}"));
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data("{\"sessionId\": \"" + sessionId + "\", \"reply\": \"" + escapeJson(reply) + "\"}"));
                    emitter.complete();
                    return;
                }

                // 3. 带重试的流式调用
                log.info("[ChatService-Stream] >>> 开始流式调用 | message={}", actualQuery);
                long start = System.currentTimeMillis();

                for (int attempt = 0; attempt <= MAX_STREAM_RETRIES; attempt++) {
                    try {
                        if (attempt > 0) {
                            long delay = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
                            log.info("[ChatService-Stream] 第 {} 次重试, 等待 {}ms", attempt, delay);
                            Thread.sleep(delay);
                        }

                        StringBuilder fullReply = new StringBuilder();
                        boolean[] completed = {false};
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
                                                .data("{\"content\": \"" + escapeJson(chunk) + "\"}"));
                                    } catch (Exception e) {
                                        log.warn("[ChatService-Stream] 发送 chunk 失败: {}", e.getMessage());
                                    }
                                })
                                .doOnComplete(() -> completed[0] = true)
                                .doOnError(error -> {
                                    streamError[0] = (error instanceof Exception ex) ? ex : new RuntimeException(error);
                                })
                                .blockLast();  // 同步等待流完成

                        // 流正常完成
                        if (streamError[0] != null) throw streamError[0];

                        long elapsed = System.currentTimeMillis() - start;
                        log.info("[ChatService-Stream] <<< 流式调用完成 | 耗时={}ms, 长度={}", elapsed, fullReply.length());

                        // 4. afterRag 阶段
                        String reply = fullReply.toString();
                        PluginContext pluginCtx = new PluginContext(
                                request.message(), actualQuery, false, null);
                        reply = pluginService.executeAfterRag(reply, request.message(), request.userId(), pluginCtx);

                        // 5. 发送 done 事件
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data("{\"sessionId\": \"" + sessionId + "\", \"reply\": \"" + escapeJson(reply) + "\"}"));
                        emitter.complete();

                        // 6. 异步后处理
                        asyncExtractAndStore(sessionId, request.userId(), request.message(), reply);
                        return;  // 成功，退出重试循环

                    } catch (Exception e) {
                        boolean isConnectionError = isConnectionReset(e);
                        if (isConnectionError && attempt < MAX_STREAM_RETRIES) {
                            log.warn("[ChatService-Stream] 连接异常, 将重试 | attempt={}, error={}", attempt + 1, e.getMessage());
                            continue;
                        }
                        throw e;  // 非连接错误 或 重试次数用尽
                    }
                }

            } catch (Exception e) {
                log.error("[ChatService-Stream] 最终失败: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"message\": \"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
            }
        });
        executor.shutdown();
    }

    private boolean isConnectionReset(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connection reset")
                        || lower.contains("connection reset by peer")
                        || lower.contains("broken pipe")
                        || lower.contains("connection closed")
                        || lower.contains("unexpected end of stream")
                        || lower.contains("socket closed")
                        || lower.contains("forcibly closed")
                        || lower.contains("远程主机强迫关闭了一个现有的连接")) {
                    return true;
                }
            }
            // 检查异常类型
            String className = cause.getClass().getSimpleName();
            if (className.contains("Reset") || className.contains("Pipe")
                    || className.contains("ConnectException") || className.contains("SocketException")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

    @Async
    protected void asyncExtractAndStore(String sessionId, String userId,
                                         String userMessage, String assistantReply) {
        try {
            log.info("[Async] ---- 异步后处理开始 | sessionId={}, userId={}", sessionId, userId);

            // 存储对话到 Milvus
            log.info("[Async] 步骤1: 存储对话片段到 Milvus | sessionId={}", sessionId);
            semanticMemoryService.storeConversationChunk(userId, sessionId, userMessage, assistantReply);
            log.info("[Async] 步骤1: 对话片段存储完成 | sessionId={}", sessionId);

            // 判断是否需要提取事实（节流：每新增 4 条消息才触发一次）
            List<Message> messages = chatMemory.get(sessionId);
            int currentSize = messages.size();
            int lastSize = lastExtractedSize.getOrDefault(sessionId, 0);
            log.info("[Async] 步骤2: 当前消息数={}, 上次提取时消息数={} | sessionId={}", currentSize, lastSize, sessionId);

            if (currentSize >= 4 && currentSize - lastSize >= 4) {
                log.info("[Async] 步骤3: 触发事实提取 | sessionId={}", sessionId);
                longTermMemoryService.extractFacts(sessionId, userId, messages);
                lastExtractedSize.put(sessionId, currentSize);
                log.info("[Async] 步骤3: 事实提取完成 | sessionId={}", sessionId);
            } else {
                log.info("[Async] 步骤3: 跳过事实提取（节流） | sessionId={}", sessionId);
            }

            log.info("[Async] ---- 异步后处理完成 | sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("[Async] 异步后处理失败 | sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }
}
