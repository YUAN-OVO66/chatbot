package com.iflytek.chatbot.util;

import java.util.regex.Pattern;

/**
 * 用户标识 / 业务 ID 校验工具。
 * 用于所有进入 Milvus filterExpression、SQL 字符串拼接或日志的外部输入。
 */
public final class IdValidators {

    /** userId / conversationId: UUID 或字母数字下划线横线，最长 128 字符 */
    private static final Pattern SAFE_ID = Pattern.compile("^[\\w\\-]{1,128}$");
    /** factId / documentId: 纯数字（Long 范围内） */
    private static final Pattern SAFE_NUMERIC_ID = Pattern.compile("^\\d{1,19}$");

    private IdValidators() {}

    public static void requireSafeId(String value, String fieldName) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的 " + fieldName + " 格式");
        }
    }

    public static void requireSafeNumericId(String value, String fieldName) {
        if (value == null || !SAFE_NUMERIC_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的 " + fieldName + " 格式");
        }
    }
}
