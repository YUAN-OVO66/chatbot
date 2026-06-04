package com.iflytek.chatbot.plugin;

import java.util.List;

/**
 * 插件 afterRag 上下文
 *
 * <p>传递给 afterRag 方法，让插件了解整个请求的处理过程。</p>
 *
 * @param originalQuery     用户原始消息
 * @param actualQuery       实际查询（可能被 beforeRag 修改）
 * @param shortCircuited    是否被插件短路
 * @param shortCircuitPlugin 触发短路的插件名称
 */
public record PluginContext(
        String originalQuery,
        String actualQuery,
        boolean shortCircuited,
        String shortCircuitPlugin
) {}
