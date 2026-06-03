package com.iflytek.chatbot.controller;

import com.iflytek.chatbot.dto.Result;
import com.iflytek.chatbot.dto.SessionCreateRequest;
import com.iflytek.chatbot.dto.SessionResponse;
import com.iflytek.chatbot.entity.ChatSession;
import com.iflytek.chatbot.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
@Tag(name = "会话管理", description = "创建、查询、归档会话")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 创建新的聊天会话 */
    @PostMapping
    @Operation(summary = "创建会话", description = "为指定用户创建一个新的聊天会话")
    public Result<SessionResponse> createSession(@RequestBody SessionCreateRequest request) {
        ChatSession session = sessionService.createSession(request.userId());
        return Result.success(new SessionResponse(
                session.getId(), session.getUserId(), session.getTitle(),
                session.getSummary(), session.getCreatedAt(),
                session.getUpdatedAt(), session.getIsActive()));
    }

    /** 查询用户的会话列表（分页） */
    @GetMapping
    @Operation(summary = "会话列表", description = "分页查询指定用户的所有活跃会话")
    public Result<Page<SessionResponse>> listSessions(
            @RequestParam String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(sessionService.listUserSessions(userId, PageRequest.of(page, size)));
    }

    /** 查询单个会话详情 */
    @GetMapping("/{sessionId}")
    @Operation(summary = "会话详情", description = "根据会话ID获取会话详细信息")
    public Result<SessionResponse> getSession(@PathVariable String sessionId) {
        ChatSession session = sessionService.getSession(sessionId);
        return Result.success(new SessionResponse(
                session.getId(), session.getUserId(), session.getTitle(),
                session.getSummary(), session.getCreatedAt(),
                session.getUpdatedAt(), session.getIsActive()));
    }

    /** 归档（删除）会话 */
    @DeleteMapping("/{sessionId}")
    @Operation(summary = "归档会话", description = "将指定会话标记为已归档，不再显示在列表中")
    public Result<Void> archiveSession(@PathVariable String sessionId) {
        sessionService.archiveSession(sessionId);
        return Result.success();
    }
}
