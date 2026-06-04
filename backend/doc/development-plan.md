# 下一步开发计划：Plugin + Skill 实现方案（终版）

## 0. 现状总结

| 模块 | 状态 | 说明 |
|------|------|------|
| RAG 核心 | **已完成** | 上传 → 分块 → 向量化(Milvus) → 检索 → 注入 prompt → LLM 生成 |
| 插件机制 | **设计完成，未编码** | 确定性代码扩展，before/after LLM |
| Skill 能力 | **重新设计** | Router + Skill Agent 子代理模式，Nacos 管理元数据，SKILL.md + scripts/ |

**技术栈：**
- 向量库：Milvus | Embedding：DashScope text-embedding-v3 | LLM：DeepSeek v4-flash
- 框架：Spring Boot 3.5.14 + Spring AI 1.1.2 + spring-ai-alibaba 1.1.2.0
- Skill 注册中心：Nacos（自建 `NacosSkillRegistry`，扩展 `AbstractSkillRegistry`）

---

## 1. 整体架构

```
用户消息
  |
  v
ChatService.chat()
  |
  +-- [阶段1] PluginService.beforeRag(query)                  ← 插件层（纯代码）
  |     -> 短路？直接返回
  |     -> 继续？
  |
  +-- [阶段2] SkillRouter.route(sessionId, query)              ← Skill 路由（规则，0ms）
  |     |
  |     +-- session 有 ACTIVE skill？
  |     |     -> 是 → 直接路由到 Skill Agent（续接对话）
  |     |
  |     +-- 关键词匹配 skill？
  |     |     -> 是 → 创建 SkillState(ACTIVE) → 路由到 Skill Agent
  |     |
  |     +-- 均不匹配 → 跳过，进入阶段3
  |     |
  |     +-- [Skill Agent]                                       ← Skill 子代理（独立 LLM）
  |     |     -> 独立 system prompt（任务执行器角色）
  |     |     -> 独立对话历史（SkillState.messages）
  |     |     -> 工具：read_skill(name) + execute_script(...)
  |     |     -> 多轮交互：向用户收集信息 → 确认 → 执行脚本
  |     |     -> 完成 → 返回 COMPLETED + 结果
  |     |
  |     +-- Skill COMPLETED？
  |           -> 是 → 清除 SkillState → 结果注入主 Chat 上下文
  |           -> 否 → 直接返回 Skill Agent 回复（等待用户下一轮输入）
  |
  +-- [阶段3] ChatClient.call(query)                           ← 主 Chat（正常聊天）
  |     -> [Advisor 0] 短期记忆
  |     -> [Advisor 1] 语义记忆
  |     -> [Advisor 2] 长期记忆
  |     -> [Advisor 3] RAG 知识检索
  |     -> [Advisor 100] 日志
  |     -> DeepSeek LLM 生成回复
  |
  +-- [阶段4] PluginService.afterRag(answer, context)          ← 插件层
  |
  v
返回最终回复
```

---

## 2. Skill 完整目录结构

```
skills/                              ← Skill 根目录
  email/                             ← 邮件发送 Skill
    SKILL.md                         # 必需：指令 + 元数据（frontmatter）
    scripts/                         # 可选：可执行脚本
      send_email.sh                  #   发送邮件的脚本
      send_email.py                  #   或 Python 脚本
      validate_email.py              #   邮箱验证脚本
    references/                      # 可选：参考文档
      email_template.md              #   邮件模板说明
      smtp_config.md                 #   SMTP 配置文档
    assets/                          # 可选：模板、资源
      template.html                  #   HTML 邮件模板
      logo.png                       #   签名 logo

  weather/                           ← 天气查询 Skill
    SKILL.md
    scripts/
      get_weather.py                 # 调用天气 API 的脚本
    references/
      api_docs.md                    # 天气 API 文档

  research/                          ← 深度研究 Skill
    SKILL.md
    scripts/
      web_search.py                  # 网络搜索脚本
      summarize.py                   # 文本摘要脚本
    references/
      search_engines.md              # 搜索引擎说明
    assets/
      report_template.md             # 研究报告模板
```

**SKILL.md 格式：**

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

- `scripts/validate_email.py` — 验证邮箱格式
  - 参数: <邮箱地址>
  - 返回: "valid" 或 "invalid"

## 注意事项

- 用户说"取消"时终止流程
- 发送前必须确认
```

---

## 3. Nacos Skill Registry 设计

### 3.1 为什么用 Nacos

| 维度 | FileSystemSkillRegistry | NacosSkillRegistry（自建） |
|------|------------------------|---------------------------|
| 部署 | 跟随项目本地目录 | 集中管理，多实例共享 |
| 更新 | 改文件 + reload | Nacos 配置推送，实时生效 |
| 版本管理 | 无 | Nacos 配置版本历史 |
| 多环境 | 每个环境各一份 | Nacos namespace 隔离 |
| 权限 | 文件系统权限 | Nacos ACL |

### 3.2 实现方案

`NacosSkillRegistry` 继承 `AbstractSkillRegistry`，从 Nacos Config 读取 Skill 元数据，从本地文件系统加载 Skill 内容（SKILL.md + scripts）。

```
Nacos Config (skill 元数据)
  |
  +-- data-id: chatbot-skills
  +-- group: DEFAULT_GROUP
  +-- content: JSON
      {
        "skills": [
          {
            "name": "email",
            "description": "代表用户发送电子邮件",
            "skillPath": "/opt/chatbot/skills/email",
            "enabled": true
          },
          {
            "name": "weather",
            "description": "查询城市天气信息",
            "skillPath": "/opt/chatbot/skills/weather",
            "enabled": true
          }
        ]
      }

本地文件系统 (Skill 内容)
  /opt/chatbot/skills/
    email/
      SKILL.md + scripts/ + references/ + assets/
    weather/
      SKILL.md + scripts/ + references/ + assets/
```

### 3.3 NacosSkillRegistry 核心代码设计

```java
package com.iflytek.chatbot.skill.registry;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.AbstractSkillRegistry;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于 Nacos 的 Skill 注册中心
 *
 * <p>从 Nacos Config 读取 Skill 元数据列表，
 * 从本地文件系统加载 SKILL.md 内容和脚本。</p>
 *
 * <p>支持 Nacos 配置推送实时更新。</p>
 */
public class NacosSkillRegistry extends AbstractSkillRegistry {

    private final ConfigService configService;
    private final String dataId;
    private final String group;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private NacosSkillRegistry(Builder builder) {
        this.configService = builder.configService;
        this.dataId = builder.dataId;
        this.group = builder.group;

        if (builder.autoLoad) {
            loadSkillsToRegistry();
            registerNacosListener();
        }
    }

    @Override
    protected void loadSkillsToRegistry() {
        try {
            // 1. 从 Nacos 读取配置
            String config = configService.getConfig(dataId, group, 5000);

            // 2. 解析 JSON 为 SkillMeta 列表
            SkillConfig skillConfig = objectMapper.readValue(config, SkillConfig.class);

            // 3. 构建 SkillMetadata（从本地文件加载完整内容）
            Map<String, SkillMetadata> skills = new HashMap<>();
            for (SkillMeta meta : skillConfig.getSkills()) {
                if (!meta.isEnabled()) continue;

                SkillMetadata metadata = SkillMetadata.builder()
                        .name(meta.getName())
                        .description(meta.getDescription())
                        .skillPath(meta.getSkillPath())
                        .source("nacos")
                        .build();

                skills.put(meta.getName(), metadata);
            }

            this.skills = skills;
            log.info("[NacosSkillRegistry] 加载 {} 个 Skill: {}",
                    skills.size(), skills.keySet());

        } catch (Exception e) {
            log.error("[NacosSkillRegistry] 加载 Skill 配置失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 注册 Nacos 监听器，配置变更时自动重载
     */
    private void registerNacosListener() {
        try {
            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() { return null; }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("[NacosSkillRegistry] 收到配置变更通知，重新加载 Skill");
                    reload();
                }
            });
        } catch (Exception e) {
            log.error("[NacosSkillRegistry] 注册监听器失败: {}", e.getMessage(), e);
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private ConfigService configService;
        private String dataId = "chatbot-skills";
        private String group = "DEFAULT_GROUP";
        private boolean autoLoad = true;

        public Builder configService(ConfigService cs) { this.configService = cs; return this; }
        public Builder dataId(String id) { this.dataId = id; return this; }
        public Builder group(String g) { this.group = g; return this; }
        public Builder autoLoad(boolean al) { this.autoLoad = al; return this; }
        public NacosSkillRegistry build() { return new NacosSkillRegistry(this); }
    }
}
```

---

## 4. execute_script 工具设计

### 4.1 核心概念

Skill 不再需要为每个工具写 Java `@Tool` 类。LLM 通过 `execute_script` 工具直接调用 Skill 目录中的脚本。

```
LLM 需要发送邮件
  → 读取 email Skill 的 SKILL.md（通过 read_skill）
  → SKILL.md 中描述了 scripts/send_email.sh 的用法
  → LLM 调用 execute_script("email", "send_email.sh", ["--to", "xx@xx.com", "--subject", "会议", "--body", "内容"])
  → 工具执行脚本，返回结果
```

### 4.2 execute_script 工具实现

```java
package com.iflytek.chatbot.skill.tools;

/**
 * 执行 Skill 目录中的脚本
 *
 * <p>LLM 通过此工具调用 Skill 附带的脚本，无需为每个工具写 Java 代码。</p>
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>只能执行 skills/ 目录下 scripts/ 子目录中的脚本</li>
 *   <li>脚本必须有可执行权限</li>
 *   <li>执行超时 30 秒</li>
 *   <li>禁止路径穿越（../）</li>
 * </ul>
 */
@Component
public class ExecuteScriptTool {

    private static final Logger log = LoggerFactory.getLogger(ExecuteScriptTool.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SKILLS_ROOT = "./skills";

    @Tool(description = """
        执行指定 Skill 目录中的脚本。
        参数:
        - skillName: Skill 名称（如 "email"）
        - scriptName: 脚本文件名（如 "send_email.sh"）
        - args: 脚本参数列表
        返回: 脚本的标准输出
        """)
    public String executeScript(
            @ToolParam(description = "Skill 名称") String skillName,
            @ToolParam(description = "脚本文件名") String scriptName,
            @ToolParam(description = "脚本参数") List<String> args) {

        log.info("[ExecuteScript] skill={}, script={}, args={}", skillName, scriptName, args);

        // 1. 安全校验
        validatePath(skillName, scriptName);

        // 2. 构建脚本路径
        Path scriptPath = Path.of(SKILLS_ROOT, skillName, "scripts", scriptName);
        File scriptFile = scriptPath.toFile();

        if (!scriptFile.exists()) {
            return "Error: 脚本不存在: " + scriptPath;
        }
        if (!scriptFile.canExecute()) {
            return "Error: 脚本没有执行权限: " + scriptPath;
        }

        // 3. 执行脚本
        try {
            List<String> command = new ArrayList<>();
            command.add(scriptFile.getAbsolutePath());
            command.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(scriptFile.getParentFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return "Error: 脚本执行超时（" + TIMEOUT.toSeconds() + "秒）";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "Error (exit=" + exitCode + "): " + output;
            }

            log.info("[ExecuteScript] 执行成功 | output={}", output.length() > 200
                    ? output.substring(0, 200) + "..." : output);
            return output;

        } catch (Exception e) {
            log.error("[ExecuteScript] 执行异常: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    private void validatePath(String skillName, String scriptName) {
        if (skillName.contains("..") || scriptName.contains("..")) {
            throw new SecurityException("路径穿越不允许");
        }
        if (!skillName.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            throw new SecurityException("非法 Skill 名称: " + skillName);
        }
        if (!scriptName.matches("^[a-zA-Z0-9_\\-\\.]+$")) {
            throw new SecurityException("非法脚本文件名: " + scriptName);
        }
    }
}
```

### 4.3 脚本规范

脚本必须遵循以下规范才能被 LLM 正确调用：

| 规范 | 说明 |
|------|------|
| 位置 | `skills/<skill-name>/scripts/<script-name>` |
| 权限 | 必须有可执行权限（`chmod +x`） |
| 输入 | 通过命令行参数传入（`--key value` 格式） |
| 输出 | stdout 作为返回值（纯文本，不超过 4KB） |
| 退出码 | 0 = 成功，非 0 = 失败 |
| 超时 | 30 秒 |
| 语言 | Shell(.sh) / Python(.py) / 任意可执行文件 |

**示例：send_email.sh**

```bash
#!/bin/bash
# 发送邮件脚本
# 用法: ./send_email.sh --to user@example.com --subject "主题" --body "内容"

TO=""
SUBJECT=""
BODY ""

while [[ $# -gt 0 ]]; do
    case $1 in
        --to) TO="$2"; shift 2 ;;
        --subject) SUBJECT="$2"; shift 2 ;;
        --body) BODY="$2"; shift 2 ;;
        *) echo "Unknown parameter: $1"; exit 1 ;;
    esac
done

if [ -z "$TO" ] || [ -z "$SUBJECT" ] || [ -z "$BODY" ]; then
    echo "Error: --to, --subject, --body are required"
    exit 1
fi

# 调用邮件 API（示例用 curl）
RESPONSE=$(curl -s -X POST "https://api.email-service.com/send" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $EMAIL_API_KEY" \
    -d "{\"to\":\"$TO\",\"subject\":\"$SUBJECT\",\"body\":\"$BODY\"}")

echo "邮件已发送给 $TO"
```

**示例：get_weather.py**

```python
#!/usr/bin/env python3
"""天气查询脚本"""
import sys
import json
import urllib.request

city = sys.argv[1] if len(sys.argv) > 1 else "北京"
api_key = os.environ.get("WEATHER_API_KEY", "")

url = f"https://api.weather.com/v1/current?key={api_key}&city={city}"
with urllib.request.urlopen(url) as resp:
    data = json.loads(resp.read())
    print(f"{city}今天{data['condition']}，气温{data['temp']}°C，湿度{data['humidity']}%")
```

---

## 5. 插件模块（Plugin）— 不变

> 与之前设计一致：确定性代码，`ChatPlugin` 接口，`beforeRag()`/`afterRag()`，支持短路。
> 详见 `doc/plugin-design.md`。

---

## 6. Skill Agent 设计

### 6.1 架构定位

Skill Agent 是一个**独立的子代理**，拥有自己的 LLM 调用、system prompt、对话历史和工具集。它与主 Chat 完全解耦，仅通过 SkillRouter 进行连接。

```
                    ┌─────────────────────────────────────┐
                    │            SkillRouter              │
                    │  (规则匹配，管理 SkillState 状态机)   │
                    └────────┬──────────────┬─────────────┘
                             │              │
                    路由到 Skill Agent    跳过（进主 Chat）
                             │
                    ┌────────▼─────────────┐
                    │      Skill Agent     │
                    │  ┌─────────────────┐ │
                    │  │ System Prompt   │ │  "你是任务执行器..."
                    │  │ Tools:          │ │
                    │  │  - read_skill   │ │  加载 SKILL.md
                    │  │  - execute_script│ │  执行脚本
                    │  │ Message History │ │  Skill 自己的对话记录
                    │  └─────────────────┘ │
                    └──────────────────────┘
```

### 6.2 SkillRouter 设计

```java
@Component
public class SkillRouter {

    private final NacosSkillRegistry skillRegistry;
    private final SkillAgent skillAgent;

    // 每个 session 的 Skill 状态（内存，不持久化）
    private final Map<String, SkillState> activeSkills = new ConcurrentHashMap<>();

    /**
     * 路由决策
     * @return SkillRouteResult: 包含是否拦截、回复内容、是否完成
     */
    public SkillRouteResult route(String sessionId, String userId, String query) {
        // 1. 检查是否有进行中的 Skill
        SkillState state = activeSkills.get(sessionId);
        if (state != null && state.isActive()) {
            // 续接对话：直接路由到 Skill Agent
            SkillAgentResult result = skillAgent.continueSkill(state, query);
            if (result.isCompleted()) {
                activeSkills.remove(sessionId);
                return SkillRouteResult.completed(result.getReply());
            }
            return SkillRouteResult.inProgress(result.getReply());
        }

        // 2. 关键词匹配：检测用户消息是否命中某个 Skill
        String matchedSkill = matchSkillByKeywords(query);
        if (matchedSkill != null) {
            // 创建新的 SkillState
            SkillMetadata metadata = skillRegistry.getSkill(matchedSkill);
            SkillState newState = new SkillState(matchedSkill, userId, metadata);
            activeSkills.put(sessionId, newState);

            SkillAgentResult result = skillAgent.startSkill(newState, query);
            if (result.isCompleted()) {
                activeSkills.remove(sessionId);
                return SkillRouteResult.completed(result.getReply());
            }
            return SkillRouteResult.inProgress(result.getReply());
        }

        // 3. 无 Skill 匹配，进入正常 Chat
        return SkillRouteResult.noMatch();
    }

    /**
     * 关键词匹配（规则，不调 LLM）
     * 匹配 Skill 的 name 和 description 中的关键词
     */
    private String matchSkillByKeywords(String query) {
        String lowerQuery = query.toLowerCase();
        for (SkillMetadata skill : skillRegistry.getAllSkills().values()) {
            // 匹配 skill name（如 "发邮件" 命中 email skill）
            // 匹配 description 中的关键词（如 "天气" 命中 weather skill）
            if (matchesKeywords(lowerQuery, skill)) {
                return skill.getName();
            }
        }
        return null;
    }
}
```

### 6.3 SkillState 设计

```java
public class SkillState {
    private final String skillName;           // "email"
    private final String userId;              // 用户 ID
    private final SkillMetadata metadata;     // Skill 元数据
    private final List<Message> messages;     // Skill Agent 自己的对话历史
    private final LocalDateTime startTime;
    private SkillStatus status;               // ACTIVE / COMPLETED / CANCELLED

    public enum SkillStatus { ACTIVE, COMPLETED, CANCELLED }
}
```

**超时机制：** SkillState 创建后 **10 分钟** 未完成自动标记为 CANCELLED，下次 Router 检测到时清除。

### 6.4 Skill Agent 实现

```java
@Component
public class SkillAgent {

    private final ChatClient skillChatClient;  // 独立的 ChatClient（独立 system prompt + tools）

    public SkillAgent(ChatClient.Builder builder,
                      ReadSkillTool readSkillTool,
                      ExecuteScriptTool executeScriptTool) {
        this.skillChatClient = builder
                .defaultSystem("""
                    你是一个任务执行器。你的职责是根据 Skill 指令完成用户交给你的任务。

                    工作流程：
                    1. 如果还没有读取 Skill 指令，先调用 read_skill 加载 SKILL.md
                    2. 按照 SKILL.md 中的流程，向用户收集必要信息
                    3. 信息齐全后，向用户确认
                    4. 用户确认后，调用 execute_script 执行脚本
                    5. 返回执行结果

                    规则：
                    - 严格按照 SKILL.md 的指令执行，不要自行发挥
                    - 用户说"取消"时，回复 [CANCELLED] 并终止流程
                    - 任务完成时，回复 [COMPLETED] + 结果摘要
                    - 任务进行中，正常回复（等待用户下一轮输入）
                    """)
                .defaultTools(readSkillTool, executeScriptTool)
                .build();
    }

    /**
     * 启动新的 Skill 任务
     */
    public SkillAgentResult startSkill(SkillState state, String firstMessage) {
        state.getMessages().add(new UserMessage(firstMessage));

        String reply = skillChatClient.prompt()
                .messages(state.getMessages())
                .call()
                .content();

        return processReply(state, reply);
    }

    /**
     * 继续进行中的 Skill 对话
     */
    public SkillAgentResult continueSkill(SkillState state, String userMessage) {
        state.getMessages().add(new UserMessage(userMessage));

        String reply = skillChatClient.prompt()
                .messages(state.getMessages())
                .call()
                .content();

        return processReply(state, reply);
    }

    private SkillAgentResult processReply(SkillState state, String reply) {
        state.getMessages().add(new AssistantMessage(reply));

        if (reply.contains("[COMPLETED]")) {
            state.setStatus(SkillStatus.COMPLETED);
            String cleanReply = reply.replace("[COMPLETED]", "").trim();
            return SkillAgentResult.completed(cleanReply);
        }
        if (reply.contains("[CANCELLED]")) {
            state.setStatus(SkillStatus.CANCELLED);
            return SkillAgentResult.cancelled("任务已取消");
        }
        return SkillAgentResult.inProgress(reply);
    }
}
```

### 6.5 SkillRouteResult 设计

```java
public record SkillRouteResult(
    RouteType type,       // NO_MATCH / IN_PROGRESS / COMPLETED
    String reply,         // 回复内容（IN_PROGRESS 和 COMPLETED 时有值）
    boolean intercepted   // true = 跳过主 Chat，直接返回 reply
) {
    public enum RouteType { NO_MATCH, IN_PROGRESS, COMPLETED }

    public static SkillRouteResult noMatch() {
        return new SkillRouteResult(RouteType.NO_MATCH, null, false);
    }
    public static SkillRouteResult inProgress(String reply) {
        return new SkillRouteResult(RouteType.IN_PROGRESS, reply, true);
    }
    public static SkillRouteResult completed(String reply) {
        return new SkillRouteResult(RouteType.COMPLETED, reply, true);
    }
}
```

---

## 7. 主 Chat Advisor 链

| Order | Advisor | 来源 | 用途 |
|-------|---------|------|------|
| 0 | MessageChatMemoryAdvisor | 内置 | 短期记忆 |
| 1 | VectorStoreChatMemoryAdvisor | 内置 | 语义记忆 |
| 2 | LongTermMemoryAdvisor | 自定义 | 长期记忆 |
| 3 | RagAdvisor | 自定义 | RAG 知识检索 |
| 100 | SimpleLoggerAdvisor | 内置 | 日志 |

**主 Chat 不再包含 Skill 相关组件。** Skill 由独立的 Skill Agent 处理，主 Chat 专注于正常对话。

**Skill Agent 工具列表：**

| 工具名 | 来源 | 用途 |
|--------|------|------|
| `read_skill` | ReadSkillTool（框架内置） | 按需加载 SKILL.md 完整指令 |
| `execute_script` | 自定义 | 执行 Skill 目录中的脚本 |

---

## 8. 配置方案

### 8.1 Nacos 配置（data-id: chatbot-skills）

```json
{
  "skills": [
    {
      "name": "email",
      "description": "代表用户发送电子邮件。当用户想要写邮件、发邮件时使用此技能。",
      "keywords": ["发邮件", "写邮件", "邮件", "email", "发送邮件"],
      "skillPath": "./skills/email",
      "enabled": true
    },
    {
      "name": "weather",
      "description": "查询指定城市的天气信息。当用户问天气、气温时使用此技能。",
      "keywords": ["天气", "气温", "weather", "下雨", "温度"],
      "skillPath": "./skills/weather",
      "enabled": true
    },
    {
      "name": "research",
      "description": "深度研究指定主题，搜索网络信息并生成研究报告。",
      "keywords": ["研究", "调研", "分析报告", "research"],
      "skillPath": "./skills/research",
      "enabled": true
    }
  ]
}
```

> **keywords 字段** 用于 SkillRouter 的规则匹配。Router 检测用户消息是否包含任意 keyword，命中则路由到 Skill Agent。

### 8.2 application.yml

```yaml
chatbot:
  plugins:
    enabled: true
    items:
      time: { enabled: true }
      calculator: { enabled: true }
      web-search: { enabled: false }

  skills:
    nacos:
      data-id: chatbot-skills
      group: DEFAULT_GROUP
      auto-reload: true
    directory: ./skills              # 本地 Skill 内容目录
    agent:
      timeout-minutes: 10            # SkillState 超时时间
      max-turns: 20                  # 单个 Skill 最大对话轮数

spring:
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
```

---

## 9. 实现清单

### 9.1 插件模块（Plugin）

| # | 文件 | 说明 |
|---|------|------|
| 1 | `plugin/ChatPlugin.java` | 插件接口 |
| 2 | `plugin/PluginResult.java` | beforeRag 返回值 |
| 3 | `plugin/PluginContext.java` | afterRag 上下文 |
| 4 | `plugin/PluginService.java` | 注册、过滤、执行引擎 |
| 5 | `plugin/PluginConfigProperties.java` | 配置绑定 |
| 6 | `plugin/impl/TimePlugin.java` | 时间插件 |
| 7 | `plugin/impl/CalculatorPlugin.java` | 计算器插件 |
| 8 | `plugin/impl/WebSearchPlugin.java` | 搜索回退插件 |
| 9 | `controller/PluginController.java` | 插件管理 API |
| 10 | `config/PluginConfig.java` | @EnableConfigurationProperties |

### 9.2 Skill 模块

| # | 文件 | 说明 |
|---|------|------|
| 11 | `skill/registry/NacosSkillRegistry.java` | 继承 AbstractSkillRegistry，从 Nacos 加载 Skill 元数据 |
| 12 | `skill/tools/ExecuteScriptTool.java` | execute_script 工具，执行 Skill 脚本 |
| 13 | `skill/agent/SkillAgent.java` | Skill 子代理，独立 LLM + tools + 对话历史 |
| 14 | `skill/agent/SkillRouter.java` | Skill 路由器，规则匹配 + SkillState 状态管理 |
| 15 | `skill/agent/SkillState.java` | Skill 会话状态（skillName, messages, status） |
| 16 | `skill/agent/SkillRouteResult.java` | 路由结果（NO_MATCH / IN_PROGRESS / COMPLETED） |
| 17 | `skill/agent/SkillAgentResult.java` | Agent 结果（reply, completed/cancelled） |
| 18 | `skill/config/SkillConfigProperties.java` | Skill 配置绑定（nacos dataId/group/directory/agent） |
| 19 | `config/SkillConfig.java` | 创建 NacosSkillRegistry + SkillAgent + SkillRouter + ReadSkillTool + ExecuteScriptTool |
| 20 | `controller/SkillController.java` | Skill 管理 API（列出/重载/查看活跃状态） |
| 21 | `skills/email/SKILL.md` | 邮件发送 Skill 指令 |
| 22 | `skills/email/scripts/send_email.sh` | 邮件发送脚本 |
| 23 | `skills/weather/SKILL.md` | 天气查询 Skill 指令 |
| 24 | `skills/weather/scripts/get_weather.py` | 天气查询脚本 |

### 9.3 ChatService 改造

| # | 文件 | 说明 |
|---|------|------|
| 25 | `service/ChatService.java` | 集成 SkillRouter + PluginService，改造主流程 |

### 9.4 MemoryConfig 改造

| # | 文件 | 说明 |
|---|------|------|
| 26 | `config/MemoryConfig.java` | 从 ChatClient 移除 Skill 相关 Advisor（主 Chat 不再处理 Skill） |

### 9.5 依赖变更

| # | 文件 | 说明 |
|---|------|------|
| 27 | `pom.xml` | 添加 `spring-cloud-starter-alibaba-nacos-config` |

---

## 10. 完整对话流程示例

### 场景 A：用户说"我要发邮件"（Skill 多轮交互）

```
用户: "我要发邮件"

[PluginService.beforeRag] → CONTINUE

[SkillRouter.route]
  activeSkills: 无进行中的 Skill
  关键词匹配: "发邮件" → 命中 email skill（keywords 包含 "发邮件"）
  → 创建 SkillState(email, ACTIVE)
  → 路由到 Skill Agent

[Skill Agent]
  system: "你是任务执行器..."
  tools: read_skill, execute_script
  Skill Agent: 调用 read_skill("email") → 获得 SKILL.md 指令
  Skill Agent: "请问收件人的邮箱地址是？"
  → 状态: IN_PROGRESS（等待用户下一轮）

[ChatService] intercepted=true → 跳过主 Chat，直接返回

返回: "请问收件人的邮箱地址是？"

---

用户: "zhang@example.com"

[SkillRouter.route]
  activeSkills: email=ACTIVE → 续接对话
  → 路由到 Skill Agent

[Skill Agent]
  历史: [User: "我要发邮件", Assistant: "请问收件人邮箱？", User: "zhang@example.com"]
  Skill Agent: "邮件主题是什么？"
  → 状态: IN_PROGRESS

返回: "邮件主题是什么？"

---

用户: "关于明天的会议"

[SkillRouter.route] → email=ACTIVE → Skill Agent
  Skill Agent: "请输入邮件内容："

返回: "请输入邮件内容："

---

用户: "请准时参加明天下午3点的产品评审会议"

[SkillRouter.route] → email=ACTIVE → Skill Agent
  Skill Agent: "确认发送？\n收件人: zhang@example.com\n主题: 关于明天的会议\n内容: ..."

返回: "确认发送？..."

---

用户: "确认"

[SkillRouter.route] → email=ACTIVE → Skill Agent
  Skill Agent: 调用 execute_script("email", "send_email.sh", ["--to", "zhang@example.com", ...])
  → 脚本执行，stdout: "邮件已发送给 zhang@example.com"
  Skill Agent: "[COMPLETED] 邮件已成功发送给 zhang@example.com！"
  → 状态: COMPLETED

[SkillRouter] 检测到 COMPLETED → 清除 SkillState → 回复直接返回

返回: "邮件已成功发送给 zhang@example.com！"
```

### 场景 B：用户说"今天天气怎么样"（Skill 单轮完成）

```
用户: "今天天气怎么样"

[PluginService.beforeRag] → CONTINUE

[SkillRouter.route]
  关键词匹配: "天气" → 命中 weather skill
  → 创建 SkillState(weather, ACTIVE)
  → 路由到 Skill Agent

[Skill Agent]
  调用 read_skill("weather") → SKILL.md 说：调用 get_weather.py，参数为城市名
  Skill Agent: 城市未指定，但默认用"北京"
  调用 execute_script("weather", "get_weather.py", ["北京"])
  → stdout: "北京今天晴，气温 28°C，湿度 45%"
  Skill Agent: "[COMPLETED] 北京今天天气晴朗，气温 28°C，湿度 45%，适合外出！"
  → 状态: COMPLETED

[SkillRouter] COMPLETED → 清除 → 直接返回

返回: "北京今天天气晴朗，气温 28°C，湿度 45%，适合外出！"
```

### 场景 C：普通聊天（不触发 Skill）

```
用户: "你好，最近怎么样"

[PluginService.beforeRag] → CONTINUE

[SkillRouter.route]
  关键词匹配: 无命中
  → noMatch → 跳过

[ChatClient.call] 主 Chat 正常处理
  Advisor 0-3: 注入记忆 + RAG
  DeepSeek LLM: 生成回复

返回: "我很好，有什么可以帮你的吗？"
```

---

## 11. 关键设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| Skill 处理模式 | Router + Skill Agent 子代理 | 主 Chat 保持干净，Skill 独立 LLM 专注任务执行 |
| Skill 路由 | 规则匹配（keywords 字段） | 0ms 延迟，不额外调 LLM |
| Skill 多轮状态 | 内存 Map（SkillState） | Skill 流程短生命周期，无需持久化 |
| Skill Agent 对话 | 独立 message list | 与主 Chat 隔离，不污染对话记忆 |
| 完成检测 | [COMPLETED] / [CANCELLED] 标记 | Skill Agent 在回复中标记状态，Router 解析 |
| Skill 注册中心 | Nacos（自建 NacosSkillRegistry） | 集中管理、实时推送、多环境隔离 |
| Skill 内容存储 | 本地文件系统（SKILL.md + scripts） | 脚本需要本地执行，Nacos 存元数据 |
| 工具实现 | execute_script 通用工具 | 不再为每个 Skill 写 Java 类 |
| 脚本安全 | 路径校验 + 超时 30s + 禁止路径穿越 | 防止恶意脚本执行 |
| 新增 Skill | Nacos 添加元数据 + keywords + 本地创建目录 | 无需改代码、无需重启 |
| Plugin 与 Skill | 独立共存 | Plugin 管确定性逻辑，Skill 管 LLM 驱动任务 |
| 主 Chat 与 Skill | 完全解耦 | 主 Chat 不含 Skill 组件，通过 Router 连接 |

---

## 12. 实现顺序

### Phase 1：Plugin 模块（3天）
```
Day 1: ChatPlugin + PluginResult + PluginContext + PluginService + PluginConfigProperties
Day 2: TimePlugin + CalculatorPlugin + PluginController + PluginConfig
Day 3: WebSearchPlugin + ChatService 集成 + 测试
```

### Phase 2：Skill 模块（5天）
```
Day 1: NacosSkillRegistry + SkillConfigProperties + pom.xml 加 Nacos 依赖
Day 2: ExecuteScriptTool + ReadSkillTool 配置
Day 3: SkillAgent + SkillRouter + SkillState + SkillRouteResult + SkillAgentResult
Day 4: SkillConfig（组装所有组件）+ SkillController + ChatService 集成
Day 5: SKILL.md 编写 + 脚本编写 + MemoryConfig 改造（移除 Skill Advisor）
```

### Phase 3：联调（1天）
```
Day 1: Plugin + Skill Agent + 主 Chat + RAG 全链路联调
```

**总计：约 9 个工作日**

---

## 13. Nacos 配置变更流程（运维视角）

```
新增 Skill:
  1. 在服务器创建 skills/<name>/ 目录，编写 SKILL.md + scripts/
  2. 在 Nacos 控制台修改 chatbot-skills 配置，添加新 Skill 元数据
  3. Nacos 推送变更 → NacosSkillRegistry 自动重载
  4. LLM 立即可用新 Skill（无需重启应用）

禁用 Skill:
  1. Nacos 控制台将 enabled 改为 false
  2. 推送变更 → 自动重载
  3. LLM 不再看到该 Skill

修改 Skill 指令:
  1. 编辑本地 SKILL.md 文件
  2. 调用 POST /api/skills/reload（或等待下次 auto-reload）
  3. LLM 下次 read_skill 时获取最新内容
```
