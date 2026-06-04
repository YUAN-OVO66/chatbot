# 四 Agent 架构方案评审

## 1. 方案描述

设计 4 个 Agent，通过主 Agent 编排子 Agent 的方式组织能力：

```
用户消息
  |
  v
[主 Agent] ── 意图识别 + 编排
  |
  ├─── 需要 RAG？ ──> [RAG Agent] ──> 检索知识库 ──> 返回结果
  |
  ├─── 需要插件？ ──> [Plugin Agent] ──> 调用插件 ──> 返回结果
  |
  ├─── 需要 Skill？ ──> [Skill Agent] ──> 调用工具 ──> 返回结果
  |
  v
[主 Agent] 汇总所有结果 → 生成最终回复 → 返回用户
```

---

## 2. 可行性判断

**结论：方案可行，但存在明显的设计缺陷，不建议原样实施。**

下面从多个维度逐一分析。

---

## 3. 逐维度评审

### 3.1 延迟问题（最大硬伤）

**当前流程（单 Agent）：**
```
用户消息 → [1 次 LLM 调用] → 回复
总耗时：~1-3 秒
```

**四 Agent 流程：**
```
用户消息
  → [主 Agent LLM 调用] 意图识别：需要 RAG + Skill
  → [RAG Agent LLM 调用] 检索 + 生成中间结果
  → [Skill Agent LLM 调用] 调用工具 + 生成中间结果
  → [主 Agent LLM 调用] 汇总所有结果 → 最终回复
总耗时：~4-12 秒（至少 3 次 LLM 调用）
```

**问题：** 每个 Agent 都是一次完整的 LLM 调用。简单问题（如"今天星期几"）也要走 4 次 LLM，延迟不可接受。

### 3.2 插件不需要 Agent

插件（Plugin）的本质是**确定性规则**：

- TimePlugin：正则匹配 → 返回当前时间
- CalculatorPlugin：解析表达式 → 计算结果
- WebSearchPlugin：RAG 为空时调搜索 API

这些都是**纯代码逻辑**，不涉及 LLM 推理。给插件套一个 Agent 意味着：
- 先用 LLM 判断"要不要调插件"（浪费）
- 再执行插件代码（本身就是确定性的）
- 再用 LLM 把插件结果包装成回复（多余）

**TimePlugin 原本 0ms 就能返回结果，套上 Agent 后变成 2-3 秒。**

### 3.3 Skill 用 Agent 过度

Skill 是**单轮 function calling**，LLM 决定调用哪个工具，拿到结果后直接生成回复。

```
用户："北京天气怎么样"
  → LLM 判断需要调用 getWeather("北京")
  → 执行工具，拿到天气数据
  → LLM 生成自然语言回复
```

这是一个标准的**单轮 tool calling**，ChatClient + ToolCallAdvisor 已经原生支持。不需要单独的 Skill Agent。

### 3.4 RAG Agent 的合理性

**RAG Agent 是唯一有一定合理性的设计。** 因为：

- RAG 检索本身不复杂（向量搜索），不需要 Agent
- 但如果 RAG 检索结果需要**多步处理**（如跨多文档推理、信息综合），Agent 有价值
- 然而当前场景是"检索 → 注入 prompt → LLM 生成"，单步就够

**建议：RAG 保持现有 Advisor 模式，仅在需要复杂文档推理时可选启用 RAG Agent。**

### 3.5 记忆上下文碎片化

四 Agent 架构下，记忆管理变得复杂：

| Agent | 短期记忆 | 长期记忆 | RAG 知识 | 对话历史 |
|-------|---------|---------|---------|---------|
| 主 Agent | 需要 | 需要 | 不需要 | 需要 |
| RAG Agent | 不需要 | 不需要 | 需要 | 不需要 |
| Plugin Agent | 不需要 | 不需要 | 不需要 | 不需要 |
| Skill Agent | 可能需要 | 可能需要 | 不需要 | 不需要 |

主 Agent 要把上下文传递给子 Agent，子 Agent 返回结果后主 Agent 再汇总。这导致：
- 上下文传递有信息损耗
- 每个 Agent 都要管理自己的记忆，增加复杂度
- 现有的四层 Advisor 链（短期/语义/长期/RAG）被打散到多个 Agent 中，难以复用

### 3.6 Spring AI Alibaba ReactAgent 的适用场景

项目已有的 `ReactAgent` 适合的是**单 Agent 自主多步推理**：

```
ReactAgent: "用户要订机票"
  → 思考：需要查航班 → 调用 searchFlights("北京", "上海")
  → 思考：找到 3 个航班，需要选最便宜的 → 比较价格
  → 思考：选好了，需要预订 → 调用 bookFlight(flightId)
  → 思考：预订成功 → 生成回复
```

这是一个 Agent 内部的**多步推理循环**，不是多个 Agent 之间的编排。

如果要用多 Agent 编排，Spring AI Alibaba 提供的是：
- `SequentialAgent`：顺序执行
- `ParallelAgent`：并行执行
- `SupervisorAgent`：主管 Agent 分派任务
- `LlmRoutingAgent`：LLM 路由到正确的子 Agent

这些是为**复杂工作流**设计的，不是为聊天机器人能力扩展设计的。

---

## 4. 推荐架构：单 Agent + 分层能力

```
用户消息
  |
  v
ChatService.chat()
  |
  +-- [插件层] PluginService.beforeRag()        ← 纯代码，0ms
  |     -> 短路？直接返回
  |     -> 继续？进入下一步
  |
  +-- [主流程] ChatClient.call()                ← 单次 LLM 调用
  |     -> Advisor 0: 短期记忆
  |     -> Advisor 1: 语义记忆
  |     -> Advisor 2: 长期记忆
  |     -> Advisor 3: RAG 知识检索
  |     -> Advisor 4: ToolCallAdvisor（Skill function calling）
  |     -> DeepSeek LLM 生成回复
  |     |
  |     +-- [可选] 复杂任务 → ReactAgent 模式
  |           -> 单 Agent 多步推理循环
  |           -> 思考 → 调用工具 → 观察 → 再思考...
  |
  +-- [插件层] PluginService.afterRag()         ← 纯代码，0ms
  |
  v
返回最终回复
```

**对比：**

| 维度 | 四 Agent 方案 | 单 Agent + 分层方案 |
|------|-------------|-------------------|
| LLM 调用次数 | 3-4 次/请求 | 1 次/请求（简单）或 N 次（复杂） |
| 延迟 | 4-12 秒 | 1-3 秒（简单），3-10 秒（复杂） |
| 记忆管理 | 分散在 4 个 Agent | 统一在 Advisor 链 |
| 插件延迟 | 2-3 秒（经过 Agent） | 0ms（纯代码短路） |
| 实现复杂度 | 高（Agent 间通信、上下文传递） | 中（插件 + Skill + 可选 Agent） |
| 可扩展性 | 高（独立 Agent 可独立迭代） | 中（在同一框架内扩展） |

---

## 5. 什么场景下四 Agent 方案才合理

如果未来需求演进到以下场景，多 Agent 架构才有价值：

1. **复杂的多轮任务编排**：如"帮我查一下北京到上海的机票，选最便宜的，然后订酒店"
2. **需要专门的 RAG 推理 Agent**：如"对比文档 A 和文档 B 的差异，结合最新数据给出建议"
3. **技能需要独立的对话上下文**：如 Skill Agent 需要和用户多轮交互才能完成任务
4. **Agent 需要独立的工具集和系统提示词**：不同领域 Agent 有不同的知识和能力

**建议：当前阶段采用单 Agent + 分层方案，预留 Agent 模式的扩展接口。当实际需求驱动时，再拆分为多 Agent。**

---

## 6. 总结

| 判断项 | 结论 |
|--------|------|
| 方案是否可行？ | 技术上可行，spring-ai-alibaba-agent-framework 支持多 Agent 编排 |
| 是否推荐实施？ | **不推荐**，当前场景过度设计 |
| 核心问题 | 延迟高、插件不需要 Agent、Skill 单轮就够、记忆碎片化 |
| 推荐替代方案 | 单 Agent + 分层能力（Plugin → ChatClient+Skill → 可选 ReactAgent） |
| 四 Agent 何时合理？ | 复杂多轮任务编排、跨领域专家协作场景 |

---

## 7. 妥协方案：保留 Agent 模式作为可选

如果确实希望保留"Agent"的概念，建议折中：

```
ChatService.chat()
  |
  +-- [插件层] PluginService.beforeRag()     ← 纯代码
  |
  +-- [模式判断]
  |     |
  |     +-- 简单查询 → ChatClient + Skills   ← 单次 LLM + function calling
  |     |
  |     +-- 复杂任务 → ReactAgent             ← 多步推理，单 Agent 循环
  |           -> 自带 RAG 检索能力（通过 Tool 注入）
  |           -> 自带 Skill 调用能力（通过 Tool 注入）
  |           -> 自带记忆检索能力（通过 Tool 注入）
  |
  +-- [插件层] PluginService.afterRag()      ← 纯代码
```

**关键区别：** 不是 4 个 Agent 平行编排，而是 1 个 Agent 在需要时拥有所有工具，自主决定调用顺序。这就是 `ReactAgent` 的设计初衷。
