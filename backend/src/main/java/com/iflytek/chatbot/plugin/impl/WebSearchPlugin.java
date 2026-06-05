package com.iflytek.chatbot.plugin.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.chatbot.plugin.ChatPlugin;
import com.iflytek.chatbot.plugin.PluginContext;
import com.iflytek.chatbot.plugin.PluginConfigProperties;
import com.iflytek.chatbot.plugin.PluginResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Web 搜索插件（百度 AI 搜索）
 *
 * <p>在 afterRag 阶段：如果 RAG 和 LLM 都没有给出有效回复，
 * 则调用百度 AI 搜索接口补充信息。</p>
 *
 * <p>API: POST https://qianfan.baidubce.com/v2/ai_search/web_search</p>
 */
@Component
public class WebSearchPlugin implements ChatPlugin {

    private static final Logger log = LoggerFactory.getLogger(WebSearchPlugin.class);
    private static final String SEARCH_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 显式搜索意图关键词 */
    private static final List<String> SEARCH_KEYWORDS = List.of(
            "搜索", "搜一下", "帮我搜", "帮我查", "查找", "查一下",
            "搜搜", "查询", "网上搜", "网上查", "百度", "谷歌",
            "search", "look up", "google"
    );

    private final PluginConfigProperties config;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WebSearchPlugin(PluginConfigProperties config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return "web-search";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public PluginResult beforeRag(String query, String userId) {
        return PluginResult.continueNext();
    }

    @Override
    public String afterRag(String answer, String query, String userId, PluginContext context) {
        if (!config.isPluginEnabled("web-search")) {
            return answer;
        }

        boolean explicitSearch = hasExplicitSearchIntent(query);

        // 如果用户明确要求搜索，无论 LLM 回复如何都执行搜索
        if (!explicitSearch) {
            // 否则，仅当 LLM 回复不足时才搜索
            if (answer != null && !answer.isBlank()
                    && !answer.contains("抱歉") && !answer.contains("我不知道")) {
                return answer;
            }
        }

        log.info("[WebSearchPlugin] 调用百度 AI 搜索 | explicit={}, query={}", explicitSearch, query);

        try {
            String searchResult = doSearch(query);
            if (searchResult != null && !searchResult.isBlank()) {
                // 显式搜索意图：用搜索结果替换 LLM 的回复（LLM 可能用了 shell 爬取，质量差）
                // 非显式：追加到 LLM 回复后面
                if (explicitSearch) {
                    return "**网络搜索结果：**\n" + searchResult;
                }
                return (answer != null ? answer : "") + "\n\n**网络搜索结果：**\n" + searchResult;
            }
        } catch (Exception e) {
            log.error("[WebSearchPlugin] 搜索失败: {}", e.getMessage(), e);
        }

        return answer;
    }

    private boolean hasExplicitSearchIntent(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase();
        return SEARCH_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private String doSearch(String query) {
        Map<String, String> pluginConfig = config.getPluginConfig("web-search");
        String apiKey = pluginConfig.getOrDefault("api-key", "");
        int topK = Integer.parseInt(pluginConfig.getOrDefault("top-k", "5"));

        if (apiKey.isBlank()) {
            log.warn("[WebSearchPlugin] 未配置 API key，跳过搜索");
            return null;
        }

        try {
            // 构建请求体
            String requestBody = buildRequestBody(query, topK);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseSearchResults(response.body());
            }

            log.warn("[WebSearchPlugin] 搜索返回非200: status={}, body={}",
                    response.statusCode(),
                    response.body().length() > 200
                            ? response.body().substring(0, 200) + "..."
                            : response.body());
        } catch (Exception e) {
            log.error("[WebSearchPlugin] 请求异常: {}", e.getMessage(), e);
        }
        return null;
    }

    private String buildRequestBody(String query, int topK) {
        try {
            ObjectNode root = objectMapper.createObjectNode();

            // messages
            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", query);

            // search_source
            root.put("search_source", "baidu_search_v2");

            // resource_type_filter
            ArrayNode filters = root.putArray("resource_type_filter");
            ObjectNode filter = filters.addObject();
            filter.put("type", "web");
            filter.put("top_k", topK);

            // search_recency_filter
            root.put("search_recency_filter", "year");

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    private String parseSearchResults(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            StringBuilder sb = new StringBuilder();

            // 解析 search_results 数组
            JsonNode results = root.get("search_results");
            if (results != null && results.isArray()) {
                for (JsonNode result : results) {
                    String title = result.has("title") ? result.get("title").asText() : "";
                    String snippet = result.has("snippet") ? result.get("snippet").asText() : "";
                    String url = result.has("url") ? result.get("url").asText() : "";

                    if (!snippet.isBlank()) {
                        sb.append("- **").append(title).append("**");
                        if (!url.isBlank()) {
                            sb.append(" (").append(url).append(")");
                        }
                        sb.append("\n  ").append(snippet).append("\n");
                    }
                }
            }

            // 解析 answer 字段（AI 搜索可能直接返回总结答案）
            if (sb.isEmpty()) {
                JsonNode answerNode = root.get("answer");
                if (answerNode != null && !answerNode.asText().isBlank()) {
                    sb.append(answerNode.asText());
                }
            }

            // 解析 result 字段（备用）
            if (sb.isEmpty()) {
                JsonNode resultNode = root.get("result");
                if (resultNode != null) {
                    sb.append(resultNode.toString().length() > 1000
                            ? resultNode.toString().substring(0, 1000) + "..."
                            : resultNode.toString());
                }
            }

            String parsed = sb.toString().trim();
            if (!parsed.isEmpty()) {
                return parsed;
            }

            // 最后兜底：返回原始 JSON 的前 500 字符
            return body.length() > 500 ? body.substring(0, 500) + "..." : body;

        } catch (Exception e) {
            log.warn("[WebSearchPlugin] 解析搜索结果失败，返回原始响应", e);
            return body.length() > 500 ? body.substring(0, 500) + "..." : body;
        }
    }
}
