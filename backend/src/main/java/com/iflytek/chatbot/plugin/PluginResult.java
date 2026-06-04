package com.iflytek.chatbot.plugin;

/**
 * 插件 beforeRag 返回值
 *
 * @param action       动作类型
 * @param answer       短路时的回复内容（SHORT_CIRCUIT 时有值）
 * @param modifiedQuery 修改后的查询（MODIFIED_QUERY 时有值）
 * @param pluginName   触发短路的插件名称
 */
public record PluginResult(
        Action action,
        String answer,
        String modifiedQuery,
        String pluginName
) {
    public enum Action {
        /** 继续执行下一个插件或进入 RAG */
        CONTINUE,
        /** 短路：直接返回 answer，跳过 RAG 和 LLM */
        SHORT_CIRCUIT,
        /** 修改查询：用 modifiedQuery 替换原始查询后继续 */
        MODIFIED_QUERY
    }

    /** 继续执行 */
    public static PluginResult continueNext() {
        return new PluginResult(Action.CONTINUE, null, null, null);
    }

    /** 短路返回 */
    public static PluginResult shortCircuit(String answer, String pluginName) {
        return new PluginResult(Action.SHORT_CIRCUIT, answer, null, pluginName);
    }

    /** 修改查询 */
    public static PluginResult modifiedQuery(String modifiedQuery) {
        return new PluginResult(Action.MODIFIED_QUERY, null, modifiedQuery, null);
    }
}
