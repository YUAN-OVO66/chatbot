# Skill 模块开发设计文档

## 1. 概述

Skill 是 LLM 驱动的任务执行能力层，与已实现的 Plugin（确定性代码）形成互补：

| 维度 | Plugin（已完成） | Skill（待实现） |
|------|-----------------|----------------|
| 执行方式 | 代码规则匹配，beforeRag/afterRag | LLM 自主决策，function calling |
| 交互模式 | 单次拦截 | 多轮对话收集信息 |
| 典型场景 | 时间查询、计算器 | 发邮件、查天气、深度研究 |

基于 Spring AI Alibaba Agent Framework 已有的 Skill 体系，**不需要自定义 Router/Agent/State 等组件**，直接复用框架能力。

---

## 2. 架构设计

### 2.1 框架提供的组件

框架（`spring-ai-alibaba-agent-framework`）已提供完整的 Skill 基础设施：

| 组件 | 包路径 | 作用 |
|------|--------|------|
| `ClasspathSkillRegistry` | `c.a.c.a.g.skills.registry.classpath` | 从 classpath 加载 Skill（SKILL.md + scripts） |
| `SkillsAgentHook` | `c.a.c.a.g.agent.hook.skills` | 注册 `read_skill` 工具 + 注入技能列表到系统提示 |
| `SkillPromptAugmentAdvisor` | `c.a.c.a.g.advisors` | 将技能元数据注入 ChatClient 的系统提示 |
| `ReactAgent` | `c.a.c.a.g.agent` | 多步推理 Agent，支持 Hook 机制 |
| `ShellToolAgentHook` | `c.a.c.a.g.agent.hook.shelltool` | 提供 Shell 命令执行能力 |
| `PythonTool` | `c.a.c.a.g.agent.tools` | 提供 Python 脚本执行能力 |

### 2.2 两种集成方式

**方式一：ChatClient + SkillPromptAugmentAdvisor（推荐，兼容现有架构）**

```
用户消息
  |
  v
ChatService.chat()
  |
  +-- [阶段1] PluginService.beforeRag(query)
  |
  +-- [阶段2] SkillPromptAugmentAdvisor 注入技能列表到系统提示
  |     +-- read_skill 工具可用
  |     +-- LLM 自主决定是否调用 read_skill
  |     +-- LLM 根据 SKILL.md 内容决定后续操作
  |
  +-- [阶段3] ChatClient.call(query) + Advisor 链
  |     -> [Advisor 0] 短期记忆
  |     -> [Advisor 1] 语义记忆
  |     -> [Advisor 2] 长期记忆
  |     -> [Advisor 3] RAG 知识检索
  |     -> [Advisor 5] SkillPromptAugmentAdvisor（技能发现）
  |     -> [Advisor 100] 日志
  |     -> DeepSeek LLM 生成回复（可调用 read_skill）
  |
  +-- [阶段4] PluginService.afterRag(answer, context)
  |
  v
返回最终回复
```

**方式二：ReactAgent + SkillsAgentHook（独立 Agent 模式）**

```
用户消息
  |
  v
ChatService.chat()
  |
  +-- [阶段1] PluginService.beforeRag(query)
  |
  +-- [阶段2] 判断是否需要 Agent 模式
  |     |
  |     +-- [普通模式] ChatClient.call()（正常聊天）
  |     |
  |     +-- [Agent 模式] ReactAgent.call()
  |           -> SkillsAgentHook: read_skill 工具 + 技能列表
  |           -> ShellToolAgentHook: Shell 执行
  |           -> PythonTool: Python 执行
  |           -> 多步推理：read_skill → 收集信息 → execute_script
  |
  +-- [阶段3] PluginService.afterRag(answer, context)
  |
  v
返回最终回复
```

### 2.3 推荐方案

**采用方式一（ChatClient + SkillPromptAugmentAdvisor）**，理由：

1. **最小改动**：不替换现有 ChatClient，只新增一个 Advisor
2. **兼容现有记忆体系**：四层 Advisor 链完整保留
3. **渐进式披露**：LLM 先看到技能列表，按需调用 `read_skill` 加载详情
4. **后续可扩展**：如需多步推理，可额外引入 ReactAgent 作为可选模式

---

## 3. 详细设计

### 3.1 ClasspathSkillRegistry 配置

技能文件放在 `src/main/resources/skills/` 下，随 JAR 打包：

```
src/main/resources/skills/
  email/
    SKILL.md
    scripts/
      send_email.sh
  weather/
    SKILL.md
    scripts/
      get_weather.py
  research/
    SKILL.md
    scripts/
      web_search.py
      summarize.py
```

注册表构建：

```java
SkillRegistry registry = ClasspathSkillRegistry.builder()
        .classpathPath("skills")
        .build();
```

### 3.2 SkillsAgentHook 配置

```java
SkillsAgentHook skillsHook = SkillsAgentHook.builder()
        .skillRegistry(registry)
        .autoReload(true)   // 支持热重载
        .build();
```

Hook 自动完成：
- 注册 `read_skill(name)` 工具到 Agent/ChatClient
- 将技能列表（name + description + skillPath）注入系统提示
- 模型调用 `read_skill("email")` 后加载完整 SKILL.md 内容

### 3.3 SkillPromptAugmentAdvisor 集成到 ChatClient

```java
// 构建 Advisor
SkillPromptAugmentAdvisor skillAdvisor = SkillPromptAugmentAdvisor.builder()
        .skillRegistry(registry)
        .build();

// 注册到 ChatClient
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("...")
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).order(0).build(),
                VectorStoreChatMemoryAdvisor.builder(vectorStore).defaultTopK(5).order(1).build(),
                longTermMemoryAdvisor,          // order=2
                ragAdvisor,                     // order=3
                skillAdvisor,                   // order=5（技能发现）
                new SimpleLoggerAdvisor()       // order=100
        )
        .build();
```

注意：`SkillPromptAugmentAdvisor` 仅注入技能列表到系统提示，**不注册 `read_skill` 工具**。需要同时注册 `read_skill` 工具到 ChatClient：

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(...)
        .defaultToolCallbacks(skillReadToolCallback)  // read_skill 工具
        .build();
```

### 3.4 SKILL.md 格式

```markdown
---
name: email
description: 代表用户发送电子邮件。当用户想要写邮件、发邮件时使用此技能。
---

# 邮件发送技能

## 流程

1. 收集收件人邮箱、邮件主题、邮件正文
2. 确认信息后调用脚本发送

## 可用脚本

- `scripts/send_email.sh` — 发送邮件
  - 参数: --to <邮箱> --subject <主题> --body <内容>
  - 返回: "邮件已发送" 或错误信息

## 注意事项

- 用户说"取消"时终止流程
- 发送前必须确认
```

### 3.5 脚本执行方式

框架提供两种脚本执行能力，按需选用：

| 方式 | 组件 | 适用场景 |
|------|------|----------|
| Shell | `ShellToolAgentHook` + `ShellTool2` | Shell 脚本、系统命令 |
| Python | `PythonTool` | Python 脚本 |

在 ReactAgent 模式下，通过 Hook 注入：

```java
ShellToolAgentHook shellHook = ShellToolAgentHook.builder()
        .shellTool2(ShellTool2.builder(System.getProperty("user.dir")).build())
        .build();

ReactAgent agent = ReactAgent.builder()
        .name("skills-agent")
        .model(chatModel)
        .hooks(List.of(skillsHook, shellHook))
        .tools(PythonTool.createPythonToolCallback(PythonTool.DESCRIPTION))
        .build();
```

在 ChatClient 模式下，将 `ShellTool2` 和 `PythonTool` 注册为 `defaultToolCallbacks`。

### 3.6 渐进式工具披露（groupedTools）

可将特定工具绑定到某个 Skill，仅在模型调用 `read_skill` 后才激活：

```java
Map<String, List<ToolCallback>> groupedTools = Map.of(
        "email", List.of(emailSendTool)   // 仅在 read_skill("email") 后可用
);

SkillsAgentHook hook = SkillsAgentHook.builder()
        .skillRegistry(registry)
        .groupedTools(groupedTools)
        .build();
```

---

## 4. ChatService 改造

改动极小——仅需在 ChatClient 构建时加入 `SkillPromptAugmentAdvisor` 和 `read_skill` 工具：

```java
// MemoryConfig.java
@Bean
public ChatClient chatClient(..., SkillRegistry skillRegistry) {

    SkillPromptAugmentAdvisor skillAdvisor = SkillPromptAugmentAdvisor.builder()
            .skillRegistry(skillRegistry)
            .build();

    return ChatClient.builder(chatModel)
            .defaultSystem("...")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).order(0).build(),
                    VectorStoreChatMemoryAdvisor.builder(vectorStore).defaultTopK(5).order(1).build(),
                    longTermMemoryAdvisor,
                    ragAdvisor,
                    skillAdvisor,                // 新增
                    new SimpleLoggerAdvisor()
            )
            .defaultToolCallbacks(readSkillToolCallback(skillRegistry))  // 新增
            .build();
}
```

ChatService.chat() 方法**无需修改**，Skill 发现和工具调用由 Advisor 链自动处理。

---

## 5. 依赖变更

项目已引入 `spring-ai-alibaba-agent-framework`（含 `ClasspathSkillRegistry`、`SkillsAgentHook`、`ReactAgent` 等），**无需新增额外依赖**。

确认 BOM 版本兼容：
- `spring-ai-alibaba-bom` 1.1.2.0 已包含 agent-framework
- Skill 相关类在 `com.alibaba.cloud.ai.graph.*` 包下

---

## 6. 配置方案

### 6.1 application.yml

```yaml
chatbot:
  skills:
    directory: classpath:skills     # 技能目录（classpath 或文件系统路径）
    auto-reload: true               # 技能热重载
```

### 6.2 SkillConfigProperties

```java
@ConfigurationProperties(prefix = "chatbot.skills")
public class SkillConfigProperties {
    private String directory = "classpath:skills";
    private boolean autoReload = true;
    // getters/setters
}
```

---

## 7. 实现清单

| # | 文件 | 说明 | 工作量 |
|---|------|------|--------|
| 1 | `skill/config/SkillConfigProperties.java` | 配置绑定 | 小 |
| 2 | `config/SkillConfig.java` | 创建 ClasspathSkillRegistry + SkillPromptAugmentAdvisor + 注册 read_skill 工具 | 中 |
| 3 | `config/MemoryConfig.java` | ChatClient 中加入 Skill Advisor + read_skill 工具 | 小 |
| 4 | `controller/SkillController.java` | 管理 API（列出/重载技能） | 小 |
| 5 | `resources/skills/email/SKILL.md` | 邮件 Skill 指令 | 小 |
| 6 | `resources/skills/email/scripts/send_email.sh` | 邮件发送脚本 | 小 |
| 7 | `resources/skills/weather/SKILL.md` | 天气 Skill 指令 | 小 |
| 8 | `resources/skills/weather/scripts/get_weather.py` | 天气查询脚本 | 小 |
| 9 | `resources/application.yml` | 添加 skills 配置段 | 小 |

---

## 8. 实现顺序

### Phase 1：框架集成（#1 ~ #3）
- SkillConfigProperties 配置绑定
- SkillConfig：创建 ClasspathSkillRegistry、SkillPromptAugmentAdvisor、read_skill 工具
- MemoryConfig：ChatClient 中挂载 Skill Advisor

### Phase 2：示例 Skill + API（#4 ~ #9）
- 编写 email / weather 示例 SKILL.md 和脚本
- SkillController 管理 API
- application.yml 配置

---

## 9. 关键设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Skill 注册中心 | ClasspathSkillRegistry | 无需 Nacos，技能随 JAR 分发，简单可靠 |
| 集成方式 | ChatClient + SkillPromptAugmentAdvisor | 最小改动，兼容现有四层记忆体系 |
| 技能发现 | 渐进式披露（列表 → read_skill → 详情） | 避免系统提示过长，按需加载 |
| 脚本执行 | 框架 ShellTool2 + PythonTool | 复用框架能力，无需自定义 ExecuteScriptTool |
| 技能存储 | classpath:skills（随 JAR） | 开发部署简单，无需外部依赖 |
| 后续扩展 | 可选 ReactAgent 模式 | 需要多步推理时再引入，不影响当前架构 |

---

## 10. 与原方案对比

| 维度 | 原方案（Nacos + 自定义 Agent） | 新方案（ClasspathSkillRegistry + 框架） |
|------|-------------------------------|----------------------------------------|
| 依赖 | 需引入 Nacos 依赖 | 无需新增依赖（agent-framework 已包含） |
| 注册中心 | NacosSkillRegistry（自建） | ClasspathSkillRegistry（框架内置） |
| 技能路由 | SkillRouter（自建关键词匹配） | LLM 自主决定（read_skill 工具） |
| Skill Agent | 自建 SkillAgent + SkillState | 框架 SkillsAgentHook / SkillPromptAugmentAdvisor |
| read_skill | 自建 ReadSkillTool | 框架 SkillsAgentHook 自动注册 |
| execute_script | 自建 ExecuteScriptTool | 框架 ShellTool2 + PythonTool |
| ChatService 改动 | 大（插入 Router 拦截逻辑） | 小（仅 MemoryConfig 加 Advisor） |
| 代码量 | ~18 个文件 | ~9 个文件（含 SKILL.md 和脚本） |
| 维护成本 | 需自维护 Router/Agent/State | 框架维护，跟随版本升级 |

---

## 11. 后续扩展路径

当前方案完成后，可按需扩展：

1. **ReactAgent 模式**：对需要多步推理的 Skill（如深度研究），引入 ReactAgent + SkillsAgentHook
2. **groupedTools**：将特定工具绑定到 Skill，实现渐进式工具披露
3. **FileSystemSkillRegistry**：生产环境改为文件系统加载，支持热更新不重启
4. **自定义系统提示模板**：通过 `SystemPromptTemplate` 定制技能发现的提示格式
5. **SkillPromptAugmentAdvisor + RAG**：技能指令也可纳入向量检索，实现语义匹配
