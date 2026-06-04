# 插件 + 技能 + Agent 统一能力架构设计文档

## 1. 需求概述

在已有三层记忆体系 + RAG 知识库的基础上，为聊天机器人增加三种扩展能力：

| 能力层 | 概念 | 执行方式 | 示例 |
|--------|------|----------|------|
| **插件 (Plugin)** | 确定性规则扩展 | 代码决定，LLM 不参与 | 时间查询、计算器、Web搜索回退 |
| **技能 (Skill)** | LLM 驱动的工具调用 | LLM 决定是否调用，单轮 function calling | 天气查询、翻译、数据库查询 |
| **Agent 模式** | 自主推理与多步执行 | LLM 自主规划、多轮工具调用 | 复杂任务分解、多技能编排 |

核心问题：**是否需要将 ChatClient 切换为 Agent？**

答：**不需要完全切换，而是分层共存。** ChatClient 本身已支持 tool calling（Spring AI 1.1.2 的 `.tools()` API），足以覆盖大多数 Skill 场景。ReactAgent 仅在需要多步推理时作为可选模式启用。

---

## 2. 整体架构

### 2.1 三层能力在流程中的位置

```
用户消息
  |
  v
═══════════════════════════════════════════════════════════════
  ChatService.chat()
═══════════════════════════════════════════════════════════════
  |
  +-- [阶段1] PluginService.beforeRag(query)        ← 插件层
  |     -> TimePlugin: 正则命中 → 短路返回当前时间
  |     -> CalculatorPlugin: 表达式匹配 → 短路返回计算结果
  |     -> 其他插件: CONTINUE / MODIFIED_QUERY
  |
  +-- [阶段2] 判断是否需要 Agent 模式
  |     |
  |     +-- [普通模式] ChatClient.call().tools(skills)  ← 技能层
  |     |     -> [Advisor 0] 短期记忆
  |     |     -> [Advisor 1] 语义记忆
  |     |     -> [Advisor 2] 长期记忆
  |     |     -> [Advisor 3] RAG 知识检索
  |     |     -> [ToolCallAdvisor] LLM 自主决定是否调用 Skill
  |     |     |     -> WeatherSkill? TranslationSkill? ...
  |     |     -> [Advisor 100] 日志
  |     |     -> DeepSeek LLM 生成回复
  |     |
  |     +-- [Agent 模式] ReactAgent.call(query)        ← Agent 层
  |           -> LLM 自主规划多步任务
  |           -> 循环: 思考 → 调用工具 → 观察 → 再思考...
  |           -> 直到得出最终答案
  |
  +-- [阶段3] PluginService.afterRag(answer, context)  ← 插件层
  |     -> WebSearchPlugin: RAG 为空时搜索网络追加结果
  |     -> 其他插件: 透传 / 修改回复
  |
  v
返回最终回复
```

### 2.2 ChatClient vs ReactAgent 选型

| 维度 | ChatClient + Tools | ReactAgent |
|------|-------------------|------------|
| 工具调用 | 单轮：LLM 调用一次工具，拿到结果后生成回复 | 多轮：LLM 可反复调用工具直到任务完成 |
| 延迟 | 低（1-2 次 LLM 调用） | 高（N 次 LLM 调用，N 可能 > 5） |
| 复杂度 | 低，复用现有 ChatClient | 高，需要管理 Agent 生命周期 |
| 适用场景 | 单步工具调用：查天气、翻译、查数据库 | 多步推理：任务分解、复杂编排 |
| 与 Advisor 链兼容 | 完全兼容，工具调用在 Advisor 链内部 | 需要独立配置，不走 Advisor 链 |
| 记忆体系 | 自动继承四层记忆 | 需手动注入记忆上下文 |

**结论：默认使用 ChatClient + Tools，仅在特定场景启用 ReactAgent。**

---

## 3. 插件层设计（Plugin）

> 与 plugin-design.md 保持一致，此处仅概述核心要点。

### 3.1 核心接口

```java
public interface ChatPlugin {
    String getName();
    int getOrder();
    PluginResult beforeRag(String query, String userId);
    String afterRag(String answer, String query, String userId, PluginContext context);
}
```

### 3.2 执行规则

- `beforeRag`：按 order 升序，可 `CONTINUE` / `SHORT_CIRCUIT` / `MODIFIED_QUERY`
- `afterRag`：按 order 降序，可修改最终回复
- 在 ChatService 层执行，不走 Advisor 链

### 3.3 配置

```yaml
chatbot:
  plugins:
    enabled: true
    items:
      time: { enabled: true }
      calculator: { enabled: true }
      web-search: { enabled: false, api-key: ${WEB_SEARCH_API_KEY} }
```

---

## 4. 技能层设计（Skill）

### 4.1 核心概念

**Skill = 一组相关的 @Tool 方法 + 元数据。** LLM 通过 function calling 自主决定是否调用。

与 Plugin 的本质区别：
- Plugin 是**代码决定**执行（规则匹配 → 短路/透传）
- Skill 是**LLM 决定**执行（模型判断需要什么工具 → function calling）

### 4.2 Skill 接口

Skill 不需要自定义接口，直接使用 Spring AI 的 `@Tool` 注解。但需要一个标记接口来区分 Skill 和普通工具：

```java
package com.iflytek.chatbot.skill;

/**
 * 技能标记接口
 *
 * <p>实现此接口的 Bean 会被自动注册为 ChatClient 的可用工具。
 * LLM 通过 function calling 自主决定是否调用。</p>
 *
 * <p>与 Plugin 的区别：Plugin 由代码决定执行，Skill 由 LLM 决定执行。</p>
 */
public interface ChatSkill {

    /**
     * 技能唯一标识
     */
    String getName();

    /**
     * 技能描述（会传递给 LLM 作为工具描述的一部分）
     */
    String getDescription();

    /**
     * 执行顺序（用于注册到 ChatClient 的顺序，不影响 LLM 选择）
     */
    default int getOrder() { return 50; }

    /**
     * 此技能是否需要 Agent 模式（多步推理）
     * true: 使用 ReactAgent 执行，支持多轮工具调用
     * false: 使用 ChatClient 单轮 function calling
     */
    default boolean requiresAgent() { return false; }
}
```

### 4.3 Skill 实现示例

```java
@Component
public class WeatherSkill implements ChatSkill {

    private final RestTemplate restTemplate;

    @Override
    public String getName() { return "weather"; }

    @Override
    public String getDescription() { return "查询指定城市的当前天气信息"; }

    @Tool(description = "获取指定城市的当前天气信息，包括温度、湿度、天气状况")
    public String getWeather(
            @ToolParam(description = "城市名称，如 北京、上海、New York") String city) {
        // 调用天气 API
        return restTemplate.getForObject("https://api.weather.com/..." + city, String.class);
    }
}
```

```java
@Component
public class TranslationSkill implements ChatSkill {

    @Override
    public String getName() { return "translation"; }

    @Override
    public String getDescription() { return "将文本翻译为指定语言"; }

    @Tool(description = "将文本翻译为指定语言")
    public String translate(
            @ToolParam(description = "要翻译的文本") String text,
            @ToolParam(description = "目标语言，如 英语、日语、法语") String targetLanguage) {
        // 调用翻译 API 或 LLM 翻译
        return "...";
    }
}
```

```java
@Component
public class DatabaseQuerySkill implements ChatSkill {

    @Override
    public String getName() { return "db-query"; }

    @Override
    public String getDescription() { return "查询数据库中的业务数据"; }

    @Tool(description = "查询用户订单信息")
    public String queryOrders(
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "查询最近N条订单") int limit) {
        // 执行数据库查询
        return "...";
    }
}
```

### 4.4 Skill 注册机制

通过 `SkillService` 自动发现所有 `ChatSkill` Bean，根据配置过滤后注册到 ChatClient：

```java
@Service
public class SkillService {

    private final List<ChatSkill> allSkills;
    private final SkillConfigProperties config;
    private final Map<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();

    public SkillService(List<ChatSkill> allSkills, SkillConfigProperties config) {
        this.allSkills = allSkills;
        this.config = config;
        this.allSkills.sort(Comparator.comparingInt(ChatSkill::getOrder));
        log.info("[SkillService] 已注册 {} 个技能: {}", allSkills.size(),
                allSkills.stream().map(ChatSkill::getName).toList());
    }

    /**
     * 获取当前已启用的技能列表
     */
    public List<ChatSkill> getEnabledSkills() {
        return allSkills.stream()
                .filter(s -> isEnabled(s.getName()))
                .toList();
    }

    /**
     * 获取需要 Agent 模式的技能列表
     */
    public List<ChatSkill> getAgentSkills() {
        return getEnabledSkills().stream()
                .filter(ChatSkill::requiresAgent)
                .toList();
    }

    /**
     * 获取不需要 Agent 模式的技能列表（普通 function calling）
     */
    public List<ChatSkill> getChatSkills() {
        return getEnabledSkills().stream()
                .filter(s -> !s.requiresAgent())
                .toList();
    }

    public boolean isEnabled(String skillName) {
        Boolean override = runtimeOverrides.get(skillName);
        if (override != null) return override;
        return config.isEnabled(skillName);
    }

    public void setEnabled(String skillName, boolean enabled) {
        runtimeOverrides.put(skillName, enabled);
    }

    /**
     * 将已启用的 ChatSkill 转换为 ToolCallback[] 供 ChatClient 使用
     */
    public ToolCallback[] getChatToolCallbacks() {
        List<ChatSkill> chatSkills = getChatSkills();
        return chatSkills.stream()
                .flatMap(skill -> {
                    Object toolObject = skill;  // ChatSkill 实现类本身包含 @Tool 方法
                    return Arrays.stream(ToolCallbacks.from(toolObject));
                })
                .toArray(ToolCallback[]::new);
    }
}
```

### 4.5 Skill 配置

```yaml
chatbot:
  skills:
    enabled: true
    items:
      weather: { enabled: true }
      translation: { enabled: true }
      db-query: { enabled: true }
```

---

## 5. Agent 模式设计

### 5.1 何时启用 Agent 模式

三种触发方式：

| 触发方式 | 说明 |
|----------|------|
| **Skill 标记** | `ChatSkill.requiresAgent() == true` 的技能需要 Agent 模式 |
| **用户显式请求** | ChatRequest 中传 `mode: "agent"` |
| **自动检测**（可选） | LLM 判断任务复杂度，自动切换 |

### 5.2 ReactAgent 配置

```java
@Configuration
public class AgentConfig {

    @Bean
    public ReactAgent reactAgent(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  SkillService skillService,
                                  @Qualifier("ragVectorStore") VectorStore ragVectorStore) {
        // 收集需要 Agent 模式的技能的 ToolCallback
        List<ChatSkill> agentSkills = skillService.getAgentSkills();
        ToolCallback[] agentTools = agentSkills.stream()
                .flatMap(skill -> Arrays.stream(ToolCallbacks.from(skill)))
                .toArray(ToolCallback[]::new);

        return ReactAgent.builder()
                .model(chatModel)
                .name("chatbot-agent")
                .instruction("You are a helpful assistant. Use the available tools to complete tasks. " +
                        "Think step by step. If you need multiple tools, call them one at a time.")
                .tools(agentTools)
                .parallelToolExecution(false)
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }
}
```

### 5.3 Agent 与记忆体系的集成

ReactAgent 不走 Advisor 链，需要手动注入记忆上下文：

```java
// 在 ChatService 中调用 Agent 模式时
private String callWithAgent(String query, String userId, String sessionId) {
    // 1. 手动检索记忆上下文
    String memoryContext = buildMemoryContext(userId, sessionId, query);

    // 2. 构造带记忆的完整 prompt
    String fullPrompt = memoryContext + "\n\nUser: " + query;

    // 3. 调用 ReactAgent
    AssistantMessage response = reactAgent.call(fullPrompt);
    return response.getText();
}

private String buildMemoryContext(String userId, String sessionId, String query) {
    StringBuilder ctx = new StringBuilder();

    // 短期记忆
    List<Message> recentMessages = chatMemory.get(sessionId);
    if (!recentMessages.isEmpty()) {
        ctx.append("Recent conversation:\n");
        recentMessages.forEach(m -> ctx.append(m.getText()).append("\n"));
    }

    // 长期记忆（事实）
    List<UserMemoryFact> facts = longTermMemoryService.retrieveRelevantFacts(userId, query, 5);
    if (!facts.isEmpty()) {
        ctx.append("\nUser Memory:\n");
        facts.forEach(f -> ctx.append("- [").append(f.getCategory()).append("] ")
                .append(f.getFactText()).append("\n"));
    }

    // RAG 知识
    List<Document> chunks = ragService.searchRelevantChunks(userId, query, 5);
    if (!chunks.isEmpty()) {
        ctx.append("\nRelevant Knowledge:\n");
        chunks.forEach(c -> ctx.append(c.getText()).append("\n---\n"));
    }

    return ctx.toString();
}
```

---

## 6. ChatService 统一编排

### 6.1 完整流程

```java
@Service
public class ChatService {

    private final ChatClient chatClient;          // 普通模式
    private final ReactAgent reactAgent;           // Agent 模式
    private final PluginService pluginService;     // 插件层
    private final SkillService skillService;       // 技能层
    private final ChatMemory chatMemory;
    private final SessionService sessionService;
    private final LongTermMemoryService longTermMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final RagService ragService;

    public ChatResponse chat(ChatRequest request) {
        String sessionId = resolveSessionId(request.userId(), request.sessionId());

        // ===== 阶段1: 插件 beforeRag =====
        BeforeRagResult beforeResult = pluginService.executeBeforeRag(
                request.message(), request.userId());

        String actualQuery = beforeResult.actualQuery();
        String reply;
        boolean shortCircuited = false;
        String shortCircuitPlugin = null;

        if (beforeResult.isShortCircuit()) {
            // 插件短路：直接使用插件返回的答案
            reply = beforeResult.answer();
            shortCircuited = true;
            shortCircuitPlugin = beforeResult.pluginName();
            log.info("[ChatService] 插件短路 | plugin={}", shortCircuitPlugin);
        } else {
            // ===== 阶段2: 判断使用哪种模式 =====
            if (request.mode() == ChatMode.AGENT || hasAgentSkill(actualQuery)) {
                // Agent 模式：ReactAgent 多步推理
                reply = callWithAgent(actualQuery, request.userId(), sessionId);
            } else {
                // 普通模式：ChatClient + Skills（function calling）
                reply = callWithChatClient(actualQuery, request.userId(), sessionId);
            }
        }

        // ===== 阶段3: 插件 afterRag =====
        PluginContext pluginCtx = new PluginContext(
                request.message(), actualQuery, shortCircuited, shortCircuitPlugin,
                beforeResult.ragChunks());
        reply = pluginService.executeAfterRag(reply, request.message(), request.userId(), pluginCtx);

        // 异步后处理
        asyncExtractAndStore(sessionId, request.userId(), request.message(), reply);

        return new ChatResponse(sessionId, reply);
    }

    /**
     * 普通模式：ChatClient + Skills
     * Skills 通过 .toolCallbacks() 注入，ToolCallAdvisor 自动处理 function calling 循环
     */
    private String callWithChatClient(String query, String userId, String sessionId) {
        log.info("[ChatService] >>> 普通模式（ChatClient + Skills）");
        long start = System.currentTimeMillis();

        String reply = chatClient.prompt()
                .user(query)
                .advisors(a -> a
                        .param("chat_memory_conversation_id", sessionId)
                        .param("chat_memory_user_id", userId))
                .toolCallbacks(skillService.getChatToolCallbacks())  // 注入已启用的 Skills
                .call()
                .content();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[ChatService] <<< 普通模式返回 | 耗时={}ms", elapsed);
        return reply;
    }

    /**
     * Agent 模式：ReactAgent 多步推理
     * 手动注入记忆上下文，ReactAgent 自主规划多步工具调用
     */
    private String callWithAgent(String query, String userId, String sessionId) {
        log.info("[ChatService] >>> Agent 模式（ReactAgent）");
        long start = System.currentTimeMillis();

        String memoryContext = buildMemoryContext(userId, sessionId, query);
        String fullPrompt = memoryContext + "\n\nUser: " + query;

        AssistantMessage response = reactAgent.call(fullPrompt);
        String reply = response.getText();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[ChatService] <<< Agent 模式返回 | 耗时={}ms", elapsed);
        return reply;
    }

    private boolean hasAgentSkill(String query) {
        // 可选：基于规则或 LLM 判断是否需要 Agent 模式
        return false;
    }
}
```

### 6.2 ChatClient 配置更新

```java
@Configuration
public class MemoryConfig {

    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                  ChatMemory chatMemory,
                                  VectorStore vectorStore,
                                  LongTermMemoryAdvisor longTermMemoryAdvisor,
                                  RagAdvisor ragAdvisor,
                                  SkillService skillService,
                                  ToolCallingManager toolCallingManager) {
        return ChatClient.builder(chatModel)
                .defaultSystem("You are a helpful assistant with long-term memory. " +
                        "Use the provided user memory, conversation context, and available tools " +
                        "to give personalized and helpful responses.")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).order(0).build(),
                        VectorStoreChatMemoryAdvisor.builder(vectorStore)
                                .defaultTopK(5).order(1).build(),
                        longTermMemoryAdvisor,
                        ragAdvisor,
                        // ToolCallAdvisor 处理 function calling 循环
                        ToolCallAdvisor.builder()
                                .toolCallingManager(toolCallingManager)
                                .advisorOrder(4)
                                .build(),
                        new SimpleLoggerAdvisor()
                )
                // 注册已启用的 ChatSkill 作为默认工具
                .defaultToolCallbacks(skillService.getChatToolCallbacks())
                .build();
    }
}
```

---

## 7. 统一管理层设计

### 7.1 SkillController（技能管理 API）

```java
@RestController
@RequestMapping("/api/skills")
@Tag(name = "技能管理", description = "查看和管理 LLM 可用的技能")
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public Result<List<SkillInfo>> listSkills() {
        List<SkillInfo> skills = skillService.getEnabledSkills().stream()
                .map(s -> new SkillInfo(s.getName(), s.getDescription(),
                        s.getOrder(), s.requiresAgent(), skillService.isEnabled(s.getName())))
                .toList();
        return Result.success(skills);
    }

    @PutMapping("/{name}/enable")
    public Result<Void> enableSkill(@PathVariable String name) {
        skillService.setEnabled(name, true);
        return Result.success();
    }

    @PutMapping("/{name}/disable")
    public Result<Void> disableSkill(@PathVariable String name) {
        skillService.setEnabled(name, false);
        return Result.success();
    }
}
```

### 7.2 PluginController（插件管理 API）

```java
@RestController
@RequestMapping("/api/plugins")
@Tag(name = "插件管理", description = "查看和管理确定性插件")
public class PluginController {

    private final PluginService pluginService;

    @GetMapping
    public Result<List<PluginInfo>> listPlugins() { ... }

    @PutMapping("/{name}/enable")
    public Result<Void> enablePlugin(@PathVariable String name) { ... }

    @PutMapping("/{name}/disable")
    public Result<Void> disablePlugin(@PathVariable String name) { ... }
}
```

---

## 8. ChatRequest 扩展

```java
public record ChatRequest(
    String sessionId,
    String userId,
    String message,
    ChatMode mode       // 可选：CHAT（默认） / AGENT
) {
    // 兼容旧的三参数调用
    public ChatRequest(String sessionId, String userId, String message) {
        this(sessionId, userId, message, ChatMode.CHAT);
    }
}

public enum ChatMode {
    CHAT,    // ChatClient + Skills（默认，单轮 function calling）
    AGENT    // ReactAgent（多步推理）
}
```

---

## 9. 配置方案

### 9.1 application.yml 完整配置

```yaml
chatbot:
  # 插件配置
  plugins:
    enabled: true
    items:
      time: { enabled: true }
      calculator: { enabled: true }
      web-search:
        enabled: false
        api-key: ${WEB_SEARCH_API_KEY}
        engine: bing
        max-results: 3

  # 技能配置
  skills:
    enabled: true
    items:
      weather: { enabled: true }
      translation: { enabled: true }
      db-query: { enabled: true }

  # Agent 配置
  agent:
    enabled: true
    max-iterations: 10          # 最大工具调用轮数
    tool-timeout: 30s           # 单个工具调用超时
    parallel-tool-execution: false
```

### 9.2 ConfigProperties

```java
@ConfigurationProperties(prefix = "chatbot")
public class ChatbotConfigProperties {
    private PluginSection plugins = new PluginSection();
    private SkillSection skills = new SkillSection();
    private AgentSection agent = new AgentSection();

    public static class PluginSection {
        private boolean enabled = true;
        private Map<String, ItemConfig> items = new HashMap<>();
    }

    public static class SkillSection {
        private boolean enabled = true;
        private Map<String, ItemConfig> items = new HashMap<>();
    }

    public static class AgentSection {
        private boolean enabled = true;
        private int maxIterations = 10;
        private Duration toolTimeout = Duration.ofSeconds(30);
        private boolean parallelToolExecution = false;
    }

    public static class ItemConfig {
        private boolean enabled = true;
        private Map<String, String> config = new HashMap<>();
    }
}
```

---

## 10. 包结构

```
com.iflytek.chatbot/
  plugin/                          ← 插件层（确定性，代码决定）
    ChatPlugin.java
    PluginResult.java
    PluginContext.java
    PluginService.java
    PluginConfigProperties.java
    impl/
      TimePlugin.java
      CalculatorPlugin.java
      WebSearchPlugin.java

  skill/                           ← 技能层（LLM 驱动，function calling）
    ChatSkill.java
    SkillService.java
    SkillConfigProperties.java
    impl/
      WeatherSkill.java
      TranslationSkill.java
      DatabaseQuerySkill.java

  agent/                           ← Agent 层（多步推理）
    AgentConfig.java               ← ReactAgent 配置

  controller/
    ChatController.java            ← 聊天接口（已有）
    PluginController.java          ← 插件管理 API
    SkillController.java           ← 技能管理 API

  config/
    MemoryConfig.java              ← ChatClient + Advisor 链 + ToolCallAdvisor
    AgentConfig.java               ← ReactAgent 配置
```

---

## 11. Advisor 链顺序（最终版）

| Order | Advisor | 用途 |
|-------|---------|------|
| 0 | MessageChatMemoryAdvisor | 短期记忆 |
| 1 | VectorStoreChatMemoryAdvisor | 语义记忆 |
| 2 | LongTermMemoryAdvisor | 长期记忆 |
| 3 | RagAdvisor | RAG 知识检索 |
| **4** | **ToolCallAdvisor（新增）** | **Skill function calling 循环** |
| 100 | SimpleLoggerAdvisor | 日志 |

插件（Plugin）不走 Advisor 链，在 ChatService 层前后包裹执行。

---

## 12. 关键设计决策总结

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 是否切换为 Agent | **不切换，分层共存** | ChatClient 已支持 tool calling，Agent 仅作可选模式 |
| Skill 实现方式 | `@Tool` 注解 + `ChatSkill` 标记接口 | 零学习成本，复用 Spring AI 原生能力 |
| Skill 注册 | `ToolCallbacks.from()` 自动扫描 | Spring IoC 自动发现，无需手动注册 |
| Plugin vs Skill | 两套独立体系，ChatService 编排 | Plugin 短路能力 + Skill LLM 驱动，职责分离 |
| Agent 模式触发 | ChatRequest.mode 字段显式指定 | 用户可控，避免自动检测的不确定性 |
| Agent 记忆注入 | 手动检索拼接 | ReactAgent 不走 Advisor 链，需手动注入 |
| ToolCallAdvisor 顺序 | order=4（RAG 之后） | 先注入记忆和知识，再让 LLM 决定是否调用工具 |
| 配置管理 | YAML + 运行时 API（内存 Map） | 配置文件定义默认值，API 支持运行时切换 |

---

## 13. 实现路线图

### Phase 1：Skill 基础框架
1. 定义 `ChatSkill` 标记接口
2. 实现 `SkillService`（注册、过滤、转换为 ToolCallback）
3. 实现 `SkillConfigProperties`
4. 更新 `MemoryConfig`：添加 `ToolCallAdvisor`(order=4) + `defaultToolCallbacks`
5. 实现 `SkillController` 管理 API

### Phase 2：插件框架（与 plugin-design.md 一致）
6. 定义 `ChatPlugin`、`PluginResult`、`PluginContext`
7. 实现 `PluginService` + `PluginConfigProperties`
8. 改造 `ChatService`：嵌入插件 beforeRag/afterRag
9. 实现 `PluginController`
10. 实现 TimePlugin、CalculatorPlugin、WebSearchPlugin

### Phase 3：示例 Skill
11. 实现 `WeatherSkill`（验证 function calling 流程）
12. 实现 `TranslationSkill`（验证参数提取能力）

### Phase 4：Agent 模式
13. 实现 `AgentConfig`（ReactAgent 配置）
14. 实现 `ChatService.callWithAgent()` + 记忆上下文注入
15. 扩展 `ChatRequest` 支持 `mode` 字段
16. 实现需要 Agent 模式的复杂 Skill 示例

### Phase 5：增强
17. Skill 执行日志和统计
18. Skill 超时控制
19. Agent 模式自动检测（基于任务复杂度）
20. Skill/Plugin 数据库持久化配置

---

## 14. 数据流全景图

```
用户: "北京今天天气怎么样？"
  |
  v
┌─────────────────────────────────────────────────────────────┐
│  ChatService.chat()                                         │
│                                                             │
│  [PluginService.beforeRag]                                  │
│    TimePlugin: "天气" ≠ 时间关键词 → CONTINUE                │
│    CalculatorPlugin: 非数学表达式 → CONTINUE                 │
│    → 无短路，继续                                             │
│                                                             │
│  [ChatClient.call().tools(skills)]                          │
│    → Advisor 0: 短期记忆（注入最近对话）                      │
│    → Advisor 1: 语义记忆（检索相似历史）                      │
│    → Advisor 2: 长期记忆（检索用户事实）                      │
│    → Advisor 3: RAG 知识检索（检索知识库文档）                │
│    → DeepSeek LLM: "用户问天气，我应该调用 getWeather 工具"  │
│    → ToolCallAdvisor: 执行 WeatherSkill.getWeather("北京")  │
│    → DeepSeek LLM: "北京今天晴，25°C，湿度 40%..."           │
│    → Advisor 100: 日志                                       │
│    → 返回回复                                                │
│                                                             │
│  [PluginService.afterRag]                                   │
│    WebSearchPlugin: RAG 有结果，跳过搜索 → 透传              │
│    → 返回最终回复                                            │
└─────────────────────────────────────────────────────────────┘
  |
  v
回复: "北京今天天气晴朗，气温 25°C，湿度 40%，适合外出活动。"
```
