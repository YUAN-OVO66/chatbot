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
        log.debug("[Controller] chat | userId={}, sessionId={}", request.userId(), request.sessionId());
        return Result.success(chatService.chat(request));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式发送消息", description = "SSE流式返回AI回复，支持多轮对话记忆")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        log.debug("[Controller] stream | userId={}, sessionId={}", request.userId(), request.sessionId());
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        chatService.streamChat(request, emitter);
        return emitter;
    }

    @GetMapping("/history")
    @Operation(summary = "历史消息", description = "获取指定会话的所有历史聊天记录")
    public Result<List<ChatMessage>> history(@RequestParam String sessionId) {
        return Result.success(chatService.getHistory(sessionId));
    }
}
