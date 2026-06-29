package com.iflytek.chatbot.exception;

/**
 * 业务异常：message 可安全回显到前端。
 * 系统级 RuntimeException（JPA / Milvus / JSON 等）不应使用此类，
 * 以免堆栈中的 SQL 片段、表名、连接信息等被暴露。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 400;
    }

    public int getCode() {
        return code;
    }
}
