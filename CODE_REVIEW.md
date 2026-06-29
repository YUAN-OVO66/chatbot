# 代码审查报告 — chatbot

**审查范围**：`backend/src/main/java/com/iflytek/chatbot/**`（Spring Boot 3.5 + Spring AI 1.1 + DeepSeek + DashScope + Milvus + MySQL）
**审查依据**：code-review-skill（架构、安全、性能、可维护性、错误处理、可测试性）
**审查日期**：2026-06-29
**严重级别**：🔴 必须修复 / 🟡 强烈建议 / 🟢 可选优化 / 💡 建议 / 📚 知识点

---

## 总览

整体架构清晰：`ChatService` 串联 *Plugin → Advisor 链 → LLM → afterRag → 异步后处理*，四层记忆（短期窗口 / 语义检索 / 长期事实 / RAG 知识库）通过 Advisor `order` 解耦，可扩展性良好。最新的安全加固（`SemanticMemoryService` 中的 `requireSafeId`、`AsyncPostProcessor` 的 LRU 节流、`AsyncConfig` 的优雅停机）方向正确。

但仍存在多个高危安全问题（特别是 shell 工具暴露 + 环境变量注入）、若干性能瓶颈（N+1 查询、O(N) 向量检索）、以及一些可维护性/正确性问题，需重点关注。

| 类别 | 🔴 | 🟡 | 🟢 |
|---|---|---|---|
| 安全 | 5 | 3 | 1 |
| 正确性 | 2 | 4 | 2 |
| 性能 | 1 | 4 | 2 |
| 可维护性 | 0 | 3 | 5 |

---

## 🔴 [blocking] 高优先级问题（建议合入前修复）

### 1. Shell 工具任意命令执行（RCE 风险面过大）

**位置**：`SkillConfig.java:75-148`

`shellToolCallback` 把任意命令字符串交给 `ProcessBuilder` 执行，并作为 ToolCallback 暴露给 LLM。模型可被 prompt 注入诱导执行任意命令；任何能调 `/api/chat` 的用户即可间接控制后端进程。

```java
ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
```

此外：
- `command.split("\\s+")` 不能正确处理引号 / 含空格的路径参数，会被静默拆碎。
- 30 秒超时不限制 CPU / 内存 / 磁盘 / 网络。
- Windows 上的 `command.matches("^/.*\\.py\\s.*")` 拼接路径会被自动加 `python` 前缀，绕过更不显眼。

**建议**：
- 限定白名单：只允许运行 `skills/<name>/scripts/` 下、已注册技能内的脚本；禁止解释器外的任意命令。
- 命令使用结构化参数（`List<String>`），而非字符串 split。
- 在沙箱（容器、低权限用户、文件系统只读挂载）中运行。
- 强烈建议默认禁用，仅在受信环境显式开启。

---

### 2. .env 全量注入到子进程，导致密钥泄露给 LLM 控制的脚本 ✅ 已修复（2026-06-29）

**位置**：`SkillConfig.java:117-124`（修复前）

```java
if (ps.getName().equals("dotenv") && ps.getSource() instanceof java.util.Properties props) {
    for (String key : props.stringPropertyNames()) {
        pb.environment().put(key, props.getProperty(key));
    }
}
```

`.env` 中包含 `DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、`QIANFAN_API_KEY`、`MYSQL_PASSWORD`、`MILVUS_HOST` 等。任何被 LLM 调起的 Python 脚本都能 `os.environ` 读到，配合上面的 RCE，相当于密钥默认外泄。

**建议**：
- 引入显式白名单 `chatbot.skills.passthrough-env=KEY1,KEY2`，只透传声明过的变量。
- 默认空白名单；高敏感密钥（DB 密码、API Key）不进白名单。

**修复实现**：
- [SkillConfigProperties.java](backend/src/main/java/com/iflytek/chatbot/skill/config/SkillConfigProperties.java) 新增 `passthroughEnv: List<String>` 字段，默认空列表。
- [SkillConfig.java:74-92](backend/src/main/java/com/iflytek/chatbot/config/SkillConfig.java#L74-L92) 把白名单 Set 闭包给 `executeShell`；[SkillConfig.java:119-131](backend/src/main/java/com/iflytek/chatbot/config/SkillConfig.java#L119-L131) 改为只遍历白名单 key，并跳过 `null`，不会把已有 ENV 删空。
- [application.yml:97-104](backend/src/main/resources/application.yml#L97-L104) 显式声明 `passthrough-env: [QWEATHER_API_KEY, QWEATHER_API_HOST]`，仅天气查询需要的两个非敏感变量被透传。

**效果**：默认空白名单 → LLM 调起的 Python 脚本通过 `os.environ` 读不到 DB/LLM 密钥；只有声明的 key 才会注入。

---

### 3. GlobalExceptionHandler 直接回显异常信息

**位置**：`GlobalExceptionHandler.java:18-22`

```java
@ExceptionHandler(RuntimeException.class)
public Result<Void> handleRuntimeException(RuntimeException e) {
    return Result.error(e.getMessage());
}
```

`RuntimeException` 在本项目里被当成业务异常滥用（如 `SessionService.getSession` 抛 `"Session not found: " + id`、`RagService` 抛 `"无权删除该文档"`，但 JPA / Milvus / JSON 解析失败抛出的 `RuntimeException` 也会落入此分支），可能把 SQL 片段、表名、连接 URL、堆栈中带出的路径直接吐给前端。

**建议**：
- 引入业务异常 `BusinessException`，仅这类异常回显 message。
- 其他 `RuntimeException` 一律返回脱敏文案（如 "服务器内部错误"），仅日志记录详情；同时返回 traceId 便于排查。

---

### 4. Milvus filterExpression 字符串拼接，部分调用缺校验

**位置**：
- ✅ `SemanticMemoryService.java:34-44` 已加 `requireSafeId` / `requireSafeFactId`
- ❌ `RagService.java:147` `delete("userId == '" + userId + "' && documentId == '" + documentId + "'")`
- ❌ `RagService.java:158-167` `searchRelevantChunks` 同样直接拼接 `userId`

虽然 `userId` 在 Controller 层是 `@RequestParam`，但缺少格式校验，存在表达式注入空间（如 `userId='x' || type=='rag'` 可能跨用户读到他人数据）。

**建议**：
- 把 `SemanticMemoryService.SAFE_ID` 提到公共校验工具类，所有进入 `filterExpression` 的字符串统一过校验。
- 或全面改用 `Filter.Expression` API（参数化构造），避免任何手工拼接。

---

### 5. ChatService.streamChat 在重试时会重复发送已发出的 chunk

**位置**：`ChatService.java:155-223`

```java
for (int attempt = 0; attempt <= MAX_STREAM_RETRIES; attempt++) {
    ...
    chatClient.prompt()...stream()
        .doOnNext(chunk -> {
            fullReply.append(chunk);
            emitter.send(... "delta" ...);   // 已经推到客户端
        })
        .blockLast();
    if (streamError[0] != null) throw streamError[0];
}
```

如果在流的中间断开（很常见），已经 `emitter.send(...)` 的内容无法撤销；下一次重试又从头流式输出，客户端会拼接出"前半段 + 完整段"的重复内容。

此外 `Thread.sleep(delay)` 在 `chatTaskExecutor` 线程里堵塞，重试期间占住线程池资源。

**建议**：
- 只在"未发出任何 delta 之前"重试；一旦开始发了，遇到错误就 `completeWithError` 让前端处理。
- 或前端用 message id + offset 去重；或服务端缓冲 + 一次性下发（不流式）。
- `Thread.sleep` 换成 Reactor 的 `Mono.delay` 或在 `Scheduled` 中调度。

---

## 🟡 [important] 中等优先级问题（建议在近期迭代修复）

### 6. N+1 查询：`retrieveRelevantFacts` 每条 doc 单独 `findById`

**位置**：`LongTermMemoryService.java:68-74`

```java
.map(doc -> {
    Object id = doc.getMetadata().get("factId");
    return id != null ? factRepository.findById(Long.parseLong(id.toString())).orElse(null) : null;
})
```

topK=5 时 5 次 SELECT；`consolidateMemories` 的内层循环（`removeSemanticallyDuplicateFacts`）更糟糕：N 个事实 × 每次 topK=10 → 最多 10N 次 `findById`。

**建议**：
- 改用 `factRepository.findAllById(idList)`，然后映射回顺序。
- `consolidateMemories` 整体改为：一次性把活跃 facts 全量取出 → 在内存里两两比较（已有 `calculateTextSimilarity`），按 importance 排序后做并查集去重；只在需要新计算 embedding 时调一次 Milvus。

---

### 7. `editDistance` 无长度上限 — 长事实文本会引发 CPU/内存尖刺

**位置**：`LongTermMemoryService.java:469-484`

`fact_text` 列是 `TEXT`，长度上限取决于 MySQL（最高 65535 字节）。两条 10KB 的事实做编辑距离 = 10K × 10K = 1 亿格 int 数组，会瞬间打满内存。

**建议**：
- `calculateTextSimilarity` 前判长：超过阈值（如 1024 字符）退化为只做 bigram Jaccard；或截断前 1024 字符再计算。

---

### 8. 异常类型混用，无业务异常分层

**位置**：`SessionService.java:39`、`RagService.java:118/140/143/175/178/183/186`、`LongTermMemoryService.java:170`

```java
throw new RuntimeException("Session not found: " + sessionId);
throw new RuntimeException("无权删除该文档");
```

业务校验失败与系统级 `RuntimeException` 无法在 `GlobalExceptionHandler` 中区分；HTTP 状态码统一是 500，不利于前端处理。

**建议**：
- 引入 `BusinessException(code, message)` 和 `NotFoundException`、`ForbiddenException`。
- 在 `GlobalExceptionHandler` 里映射到 4xx HTTP 状态码。

---

### 9. `AsyncPostProcessor.lastExtractedSize` 节流键无清理 + 不持久化

**位置**：`AsyncPostProcessor.java:25-31`

`LinkedHashMap` LRU 容量 500 已经避免无限增长，但：
- 重启后清零，重启刚结束的瞬间所有 session 都会被立即触发提取。
- LRU 是按访问顺序（access-order），并发场景下 `Collections.synchronizedMap` 在写 + 读时存在隐式锁竞争（对每个会话每次 chat 都会触发）。
- `chatMemory.get(sessionId)` 返回的 size 是滑动窗口（30 条）后的结果，达到上限后 `currentSize` 不再增长，触发条件 `currentSize - lastSize >= 4` 永远成立 → 退化为每 1 条就触发一次。

**建议**：
- 把"上次提取的 messageId / 时间戳 / 累计消息数"持久化到 `MemoryExtractionLog` 或会话表。
- 直接读 `MemoryExtractionLog` 最新一条作为 `lastExtractedSize`，淘汰内存 Map。

---

### 10. `fixMemoryUserMessage` 对整段历史 `saveAll`

**位置**：`ChatService.java:315-339`

```java
List<Message> messages = chatMemoryRepository.findByConversationId(sessionId);
...
messages.set(i, new UserMessage(originalMessage));
chatMemoryRepository.saveAll(sessionId, messages);
```

只为改"最后一条用户消息"，却读取并重写整段历史；当对话长度增长时是 O(n) 写入 + 网络往返。`JdbcChatMemoryRepository` 的 `saveAll` 实现通常是 delete-then-insert，写放大严重。

**建议**：
- 在调用 `chatClient.prompt().user(actualQuery)` 前，先在 ChatMemory 写入原始消息（自己 add），让 MessageChatMemoryAdvisor 不去重复加 actualQuery；或在 Advisor 层做切面，识别 PluginContext 直接替换。

---

### 11. `LongTermMemoryService.persistFacts` 内 LLM 返回结构非健壮

**位置**：`LongTermMemoryService.java:250-265`

```java
String text = (String) factData.get("text");
String category = (String) factData.getOrDefault("category", "general");
byte importance = ((Number) factData.getOrDefault("importance", 5)).byteValue();
```

风险：
- `text == null` 时 `factRepository.findByUserIdAndFactTextAndIsActive(userId, null, true)` 可能抛错或匹配异常。
- `importance` 若是字符串 `"high"` 会 ClassCastException。
- `importance` 超出 `byte` 范围（-128~127）时 `byteValue()` 静默截断，例如 256 → 0，可能误判为低重要性。

**建议**：
- 先校验 text 非空再走持久化。
- importance 加 clamp：`int v = ((Number)x).intValue(); fact.setImportance((byte)Math.max(1, Math.min(10, v)));`

---

### 12. `PluginService.isPluginEnabled` 在热路径 log.info

**位置**：`PluginService.java:117-124`

```java
public boolean isPluginEnabled(String pluginName) {
    ...
    log.info("[PluginService] isPluginEnabled | plugin={}, override={}, config={}, result={}", ...);
    return result;
}
```

每条用户消息 × 每个插件 × beforeRag/afterRag 都会触发；3 个插件就是每条消息 6 行 INFO 日志，搜索压力大时日志爆炸。

**建议**：降为 `log.trace` 或 `log.debug`。

---

### 13. `WebSearchPlugin` 静态 ObjectMapper 与共享 HttpClient

**位置**：`WebSearchPlugin.java:37, 47-49`

`ObjectMapper` 静态字段、`HttpClient` 实例字段 —— OK，但：
- 没有连接池上限或拒绝策略；外部慢响应可能拖死 `chatTaskExecutor`。
- API key 来自 `pluginConfig.getOrDefault("api-key", "")`：空字符串 `isBlank()` 才跳过，但中间状态（如配置文件占位）会带空字符串发出请求并 401。
- `parseSearchResults` 兜底返回原始 JSON 前 500 字符，可能把搜索厂商返回的错误信息（带敏感字段）传给 LLM 再回显给用户。

**建议**：
- 给 HttpClient 加 `executor` 和上限；调用层加超时熔断。
- 兜底分支只返回固定文案 "搜索结果解析失败"，不要原样回灌。

---

## 🟢 [nit] 低优先级 / 风格

### 14. 注释与实际值不一致

`RagService.java:27` 注释 `// 20MB` 而实际 `30 * 1024 * 1024`，`validateFile` 抛错也写"超过20MB限制"。`application.yml` 的 `max-file-size: 30MB`。统一为 30MB。

### 15. Advisor 代码重复

`LongTermMemoryAdvisor.before` 与 `RagAdvisor.before` 几乎是同一份模板：取 userMessage → 检索 → 构造 SystemMessage → mutate。建议抽出 `AbstractContextInjectingAdvisor`，子类只实现 `retrieve(userId, query)` 与 `formatHeader()`。

### 16. `isConnectionReset` 基于字符串匹配 + 中文断言

`ChatService.java:246-272` 用 message 文本里的中文 / 英文短语判断网络错误，依赖 OS 语言环境（"远程主机强迫关闭"是简中 Windows 才出现）。建议改判异常类型 `java.net.SocketException`、`java.io.EOFException`、Netty 的 `PrematureCloseException` 等。

### 17. `CalculatorPlugin.evaluate` 抛 `RuntimeException`

`new RuntimeException("Unexpected character: ...")` 落入 `catch (Exception)` OK，但建议改自定义 `ParseException`，避免被外层异常处理误吞。

### 18. `SkillConfig.executeShell` 的 Windows 命令改写依赖 regex 替换

`replaceFirst("^(bash(\\s+-c)?\\s+|cmd(\\s+/c)?\\s+)", "")` 等改写逻辑碎片化，难以测试。建议在抽象层处理 OS 差异，规则集中、并写单测覆盖。

### 19. `MemoryController.deleteFact` 静默吞掉异常

```java
try {
    semanticMemoryService.deleteFactDocument(factId);
} catch (Exception e) {
    // 向量删除失败不影响 MySQL 软删除
}
```

建议至少 `log.warn(... e)`，否则向量与 MySQL 长期不一致难排查。

### 20. JPA `ddl-auto: validate` + `schema-locations: classpath:schema.sql`

两者并存是合理的（外部 SQL 建表 + JPA 校验），但 `mode: always` 会在每次启动重跑 schema.sql；若 schema.sql 不是幂等的（无 `IF NOT EXISTS`），生产部署可能报错。建议改为 `mode: never`，并将 schema 迁移交给 Flyway / Liquibase。

### 21. `ChatService.streamChat` 中 `emitter.onError(t -> {})` 空回调

吞错不利于排查，至少打日志。

---

## 💡 [suggestion] 改进建议

### 22. 引入业务异常体系 + traceId

```java
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String msg) { super(msg); this.code = code; }
}
```

`GlobalExceptionHandler` 区分处理：
```java
@ExceptionHandler(BusinessException.class)  // -> 4xx，message 可回显
@ExceptionHandler(Exception.class)          // -> 500，固定文案 + traceId
```

### 23. `LongTermMemoryService.consolidateMemories` 改为批量算法

伪代码：
1. 一次性取活跃 facts 与对应 embedding（或缓存）。
2. 内存中按 importance 降序排序。
3. 用并查集 / 阈值聚类合并相似项，每组保留 importance 最高者。
4. 一次性 batch update `is_active=false`。

预期：N=100 时由当前 ~500 次向量查询降到 0 次（如复用已有向量）+ 1 次 SQL batch。

### 24. 抽出"用户标识"验证工具

将 `SemanticMemoryService.SAFE_ID` / `SAFE_FACT_ID` 模式提到 `IdValidators` 工具类，由 Controller 入口校验，Service 层始终信任 —— 单点收敛，避免遗漏。

### 25. 给 `chatTaskExecutor` 加拒绝策略与监控指标

`AsyncConfig` 没显式设置 `RejectedExecutionHandler`，默认 `AbortPolicy`：队列满会抛 `TaskRejectedException`，前端看到的是 5xx。建议：
- 设置 `CallerRunsPolicy` 让突发量在用户线程降级处理。
- 注入 Micrometer 指标：`active`, `queueSize`, `rejected`。

### 26. `extractFacts` 的 prompt 改为强制 JSON 格式

当前 prompt 依赖模型自觉返回 JSON，并用 `startsWith("```")` 兜底剥代码块；DeepSeek 支持 `response_format: json_object`，建议直接走 structured output，可靠度更高。

---

## 📚 [learning] 知识点

- `MessageWindowChatMemory` 的窗口大小 30 一旦达到上限，`chatMemory.get()` 始终返回 30 条 → 节流分支 `currentSize - lastSize` 失效（见 §9）。
- `MilvusVectorStore` 的 `filterExpression` 是 Milvus 表达式 DSL，目前 Spring AI 还未提供 builder 形式的安全 API，业内通行做法是输入侧白名单（项目已部分采用）。
- Reactor 流式 + Servlet `SseEmitter` 桥接是常见痛点，业内一般直接用 WebFlux 的 `Flux<ServerSentEvent>` 或前端 fetch + reader 实现，可考虑后续迁移。

---

## 推荐合入门槛

合入前必须修复的：**§1, §2, §3, §4, §5**（5 个 🔴）。
近期迭代建议处理：§6, §7, §8, §9, §10, §11, §13。
其余在重构 / 整理时顺手清理即可。

---

## 🎉 [praise] 做得好的部分

- `AsyncPostProcessor` 节流逻辑 + LRU 容量上限的设计，避免无限 Map 增长（思路正确，剩余问题见 §9）。
- `SemanticMemoryService` 的 `requireSafeId` 白名单校验是处理 Milvus filterExpression 的正确方式。
- 四层记忆通过 `BaseChatMemoryAdvisor.order` 解耦，可插拔，新增"语义记忆 / 长期记忆 / RAG"无需修改 ChatClient 构造逻辑。
- 插件链路 (`beforeRag` / `afterRag` + `SHORT_CIRCUIT` / `MODIFIED_QUERY` / `CONTINUE`) 的状态机定义清晰。
- `RagVectorStoreConfig` 双 collection 隔离用户记忆与知识库，避免污染。
- `AsyncConfig.setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)` 做了正确的优雅停机。
