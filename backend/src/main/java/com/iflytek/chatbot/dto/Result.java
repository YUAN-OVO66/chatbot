package com.iflytek.chatbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统一响应结果包装类
 * 所有 API 接口均通过此类返回，保证响应格式一致
 *
 * @param <T> 响应数据类型
 */
@Data
@Schema(description = "统一响应结果")
public class Result<T> {

    @Schema(description = "状态码，200表示成功", example = "200")
    private int code;

    @Schema(description = "响应提示信息", example = "操作成功")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    public Result() {}

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（无数据） */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    /** 成功（带数据） */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /** 成功（自定义消息 + 数据） */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    /** 失败（自定义消息） */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    /** 失败（自定义状态码 + 消息） */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
