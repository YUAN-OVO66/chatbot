package com.iflytek.chatbot.config;

import com.iflytek.chatbot.dto.Result;
import com.iflytek.chatbot.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * 全局异常处理器
 * - BusinessException / IllegalArgumentException：message 可安全回显
 * - 其他异常：返回脱敏文案 + traceId，详情仅记录到日志
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("[Business] {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[BadRequest] {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[Server] traceId={}, error={}", traceId, e.getMessage(), e);
        return Result.error(500, "服务器内部错误 (traceId=" + traceId + ")");
    }
}
