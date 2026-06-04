package com.iflytek.chatbot.controller;

import com.iflytek.chatbot.dto.ChatMessage;
import com.iflytek.chatbot.dto.ChatRequest;
import com.iflytek.chatbot.dto.ChatResponse;
import com.iflytek.chatbot.dto.Result;
import com.iflytek.chatbot.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@Tag(name = "聊天接口", description = "发送消息、多轮对话")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(summary = "发送消息", description = "发送一条消息并获取AI回复，支持多轮对话记忆")
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("========== [Controller] 收到聊天请求 | userId={}, sessionId={}, message={}",
                request.userId(), request.sessionId(), request.message());

        ChatResponse response = chatService.chat(request);

        String replyPreview = response.reply().length() > 80
                ? response.reply().substring(0, 80) + "..." : response.reply();
        log.info("========== [Controller] 返回响应 | sessionId={}, reply={}", response.sessionId(), replyPreview);

        return Result.success(response);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式发送消息", description = "SSE流式返回AI回复，支持多轮对话记忆")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        log.info("========== [Controller] 收到流式聊天请求 | userId={}, sessionId={}, message={}",
                request.userId(), request.sessionId(), request.message());

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        chatService.streamChat(request, emitter);
        return emitter;
    }

    @GetMapping("/history")
    @Operation(summary = "历史消息", description = "获取指定会话的所有历史聊天记录")
    public Result<List<ChatMessage>> history(@RequestParam String sessionId) {
        log.info("========== [Controller] 查询历史消息 | sessionId={} ==========", sessionId);
        List<ChatMessage> messages = chatService.getHistory(sessionId);
        log.info("========== [Controller] 返回 {} 条历史消息 ==========", messages.size());
        return Result.success(messages);
    }
}
