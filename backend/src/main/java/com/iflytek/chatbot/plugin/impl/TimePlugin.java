package com.iflytek.chatbot.plugin.impl;

import com.iflytek.chatbot.plugin.ChatPlugin;
import com.iflytek.chatbot.plugin.PluginContext;
import com.iflytek.chatbot.plugin.PluginResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
            String answer = "当前时间是：" + now;
            return PluginResult.shortCircuit(answer, getName());
        }
        return PluginResult.continueNext();
    }

    @Override
    public String afterRag(String answer, String query, String userId, PluginContext context) {
        return answer;
    }
}
