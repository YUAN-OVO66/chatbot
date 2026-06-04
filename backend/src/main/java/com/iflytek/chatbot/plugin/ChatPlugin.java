package com.iflytek.chatbot.plugin;

/**
 * 插件接口
 *
 * <p>确定性代码扩展，在 LLM 调用前后执行。
 * 与 Skill 的区别：Plugin 由代码决定执行，Skill 由 LLM 决定执行。</p>
 *
 * <p>执行规则：</p>
 * <ul>
 *   <li>beforeRag：按 order 升序执行，可短路返回</li>
 *   <li>afterRag：按 order 降序执行，可修改最终回复</li>
 * </ul>
 */
public interface ChatPlugin {

    /**
     * 插件唯一标识
     */
    String getName();

    /**
     * 执行顺序（数字越小越先执行）
     */
    int getOrder();

    /**
     * RAG 检索前执行
     *
     * @param query  用户原始消息
     * @param userId 用户 ID
     * @return 插件执行结果（CONTINUE / SHORT_CIRCUIT / MODIFIED_QUERY）
     */
    PluginResult beforeRag(String query, String userId);

    /**
     * RAG 检索后执行（LLM 生成回复之后）
     *
     * @param answer  LLM 生成的回复
     * @param query   用户原始消息
     * @param userId  用户 ID
     * @param context 插件上下文
     * @return 可能被插件修改后的回复
     */
    String afterRag(String answer, String query, String userId, PluginContext context);
}
