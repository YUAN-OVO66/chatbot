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
| 安全 | 1 | 3 | 1 |
| 正确性 | 0 | 4 | 2 |
| 性能 | 0 | 4 | 2 |
| 可维护性 | 0 | 2 | 5 |

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

## 🟡 [important] 中等优先级问题（建议在近期迭代修复）

## 🟢 [nit] 低优先级 / 风格

### 12. `CalculatorPlugin.evaluate` 抛 `RuntimeException`

`new RuntimeException("Unexpected character: ...")` 落入 `catch (Exception)` OK，但建议改自定义 `ParseException`，避免被外层异常处理误吞。

### 13. `SkillConfig.executeShell` 的 Windows 命令改写依赖 regex 替换

`replaceFirst("^(bash(\\s+-c)?\\s+|cmd(\\s+/c)?\\s+)", "")` 等改写逻辑碎片化，难以测试。建议在抽象层处理 OS 差异，规则集中、并写单测覆盖。

### 14. `MemoryController.deleteFact` 静默吞掉异常

```java
try {
    semanticMemoryService.deleteFactDocument(factId);
} catch (Exception e) {
    // 向量删除失败不影响 MySQL 软删除
}
```

建议至少 `log.warn(... e)`，否则向量与 MySQL 长期不一致难排查。

### 15. JPA `ddl-auto: validate` + `schema-locations: classpath:schema.sql`

两者并存是合理的（外部 SQL 建表 + JPA 校验），但 `mode: always` 会在每次启动重跑 schema.sql；若 schema.sql 不是幂等的（无 `IF NOT EXISTS`），生产部署可能报错。建议改为 `mode: never`，并将 schema 迁移交给 Flyway / Liquibase。

### 16. `ChatService.streamChat` 中 `emitter.onError(t -> {})` 空回调

吞错不利于排查，至少打日志。

---

## 💡 [suggestion] 改进建议

### 17. `LongTermMemoryService.consolidateMemories` 改为批量算法

伪代码：
1. 一次性取活跃 facts 与对应 embedding（或缓存）。
2. 内存中按 importance 降序排序。
3. 用并查集 / 阈值聚类合并相似项，每组保留 importance 最高者。
4. 一次性 batch update `is_active=false`。

预期：N=100 时由当前 ~500 次向量查询降到 0 次（如复用已有向量）+ 1 次 SQL batch。

### 18. 给 `chatTaskExecutor` 加拒绝策略与监控指标

`AsyncConfig` 没显式设置 `RejectedExecutionHandler`，默认 `AbortPolicy`：队列满会抛 `TaskRejectedException`，前端看到的是 5xx。建议：
- 设置 `CallerRunsPolicy` 让突发量在用户线程降级处理。
- 注入 Micrometer 指标：`active`, `queueSize`, `rejected`。

### 19. `extractFacts` 的 prompt 改为强制 JSON 格式

当前 prompt 依赖模型自觉返回 JSON，并用 `startsWith("```")` 兜底剥代码块；DeepSeek 支持 `response_format: json_object`，建议直接走 structured output，可靠度更高。

---

## 📚 [learning] 知识点

- `MessageWindowChatMemory` 的窗口大小 30 一旦达到上限，`chatMemory.get()` 始终返回 30 条 → 节流分支 `currentSize - lastSize` 失效（见 §9）。
- `MilvusVectorStore` 的 `filterExpression` 是 Milvus 表达式 DSL，目前 Spring AI 还未提供 builder 形式的安全 API，业内通行做法是输入侧白名单（项目已部分采用）。
- Reactor 流式 + Servlet `SseEmitter` 桥接是常见痛点，业内一般直接用 WebFlux 的 `Flux<ServerSentEvent>` 或前端 fetch + reader 实现，可考虑后续迁移。

---

## 推荐合入门槛

合入前必须修复的：**§1**（1 个 🔴 — Shell RCE）。
近期迭代建议处理：§2, §3, §4, §5, §6, §8。
其余在重构 / 整理时顺手清理即可。

---

## 🎉 [praise] 做得好的部分

- `AsyncPostProcessor` 节流逻辑 + LRU 容量上限的设计，避免无限 Map 增长（思路正确，剩余问题见 §9）。
- `SemanticMemoryService` 的 `requireSafeId` 白名单校验是处理 Milvus filterExpression 的正确方式。
- 四层记忆通过 `BaseChatMemoryAdvisor.order` 解耦，可插拔，新增"语义记忆 / 长期记忆 / RAG"无需修改 ChatClient 构造逻辑。
- 插件链路 (`beforeRag` / `afterRag` + `SHORT_CIRCUIT` / `MODIFIED_QUERY` / `CONTINUE`) 的状态机定义清晰。
- `RagVectorStoreConfig` 双 collection 隔离用户记忆与知识库，避免污染。
- `AsyncConfig.setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)` 做了正确的优雅停机。
