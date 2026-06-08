package com.iflytek.chatbot.plugin.impl;

import com.iflytek.chatbot.plugin.ChatPlugin;
import com.iflytek.chatbot.plugin.PluginContext;
import com.iflytek.chatbot.plugin.PluginResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 时间插件
 *
 * <p>当用户询问当前时间时，直接返回时间，不调用 LLM。</p>
 */
@Component
public class TimePlugin implements ChatPlugin {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(现在几点|当前时间|几点了|什么时间|what time|current time|now)",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<String> SEARCH_KEYWORDS = List.of(
            "搜索", "搜一下", "帮我搜", "帮我查", "查找", "查一下",
            "搜搜", "查询", "网上搜", "网上查", "百度", "谷歌",
            "search", "look up", "google"
    );

    @Override
    public String getName() {
        return "time";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public PluginResult beforeRag(String query, String userId) {
        if (TIME_PATTERN.matcher(query).find()) {
            String now = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));

            // 如果同时包含搜索意图，注入时间信息到 query，让搜索插件也能处理
            String lower = query.toLowerCase();
            boolean hasSearchIntent = SEARCH_KEYWORDS.stream().anyMatch(lower::contains);
            if (hasSearchIntent) {
                String augmentedQuery = query + "\n\n[当前时间：" + now + "]";
                return PluginResult.modifiedQuery(augmentedQuery);
            }

            return PluginResult.shortCircuit("当前时间是：" + now, getName());
        }
        return PluginResult.continueNext();
    }

    @Override
    public String afterRag(String answer, String query, String userId, PluginContext context) {
        return answer;
    }
}
