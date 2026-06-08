# ChatBot - 智能对话助手

基于 Spring AI + Vue 3 构建的智能对话系统，支持 RAG 检索增强、插件扩展、多轮对话记忆等能力。

## 项目简介

本项目是一个全栈智能对话助手，采用前后端分离架构。后端基于 Spring AI 框架集成 DeepSeek 大模型，实现了三层记忆系统（短期/中期/长期）、RAG 检索增强生成、插件系统和 Skill 扩展机制；前端使用 Vue 3 + Element Plus 构建现代化聊天界面，支持流式响应和 Markdown 渲染。

## 功能特性

### 核心能力

- **多轮对话** - 支持上下文感知的连续对话
- **流式响应** - SSE 实时推送 AI 回复，打字机效果
- **会话管理** - 创建、切换、删除会话，历史消息持久化

### 三层记忆系统

| 层级 | 存储 | 说明 |
|------|------|------|
| 短期记忆 | Spring AI ChatMemory | 当前会话的上下文窗口 |
| 中期记忆 | Milvus 向量数据库 | 对话片段的语义检索 |
| 长期记忆 | MySQL + Milvus | 从对话中提取的用户事实和偏好 |

- 自动从对话中提取用户事实和偏好（LLM 驱动）
- 语义去重 + 精确去重，避免记忆冗余
- 基于向量相似度的相关记忆检索

### RAG 检索增强

- 文档上传与向量化存储（Milvus）
- 对话时自动检索相关文档片段
- 支持 PDF 文档解析

### 插件系统

内置插件：
- **TimePlugin** - 时间查询
- **CalculatorPlugin** - 数学计算
- **WebSearchPlugin** - 网络搜索（接入百度 AI 搜索，显式搜索在 beforeRag 阶段注入结果到 query，非显式搜索在 afterRag 阶段兜底补充）

插件支持两个执行阶段：
- `beforeRag` - 预处理，可修改 query 或短路跳过 LLM
- `afterRag` - 后处理，增强回复内容

### Skill 系统

可扩展的技能模块，支持 Python 脚本执行：
- 天气查询（接入和风天气 API，支持实时天气和 3 天预报）
- 邮件发送
- DevOps 工具（系统信息、进程监控、端口检测）
- SQL 生成与验证
- 翻译助手
- 文本摘要

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.14 | 应用框架 |
| Spring AI | 1.1.2 | AI 集成框架 |
| DeepSeek | - | 大语言模型 |
| DashScope | - | 阿里云 AI 服务 |
| Spring Data JPA | - | 数据持久化 |
| MySQL | - | 关系型数据库 |
| Milvus | - | 向量数据库 |
| Knife4j | 4.4.0 | API 文档 |
| Java | 17 | 运行环境 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5 | 前端框架 |
| TypeScript | 6.0 | 类型系统 |
| Vite | 8.0 | 构建工具 |
| Element Plus | 2.14 | UI 组件库 |
| Pinia | 3.0 | 状态管理 |
| Vue Router | 5.1 | 路由管理 |
| Shiki | 3.23 | 代码高亮 |
| x-markdown-vue | - | Markdown 渲染 |

## 项目结构

```
chatbot/
├── frontend/                    # 前端项目
│   ├── src/
│   │   ├── api/                 # API 接口层
│   │   │   ├── chat.ts          # 聊天接口
│   │   │   ├── session.ts       # 会话接口
│   │   │   ├── memory.ts        # 记忆接口
│   │   │   ├── rag.ts           # RAG 接口
│   │   │   ├── plugin.ts        # 插件接口
│   │   │   └── skill.ts         # Skill 接口
│   │   ├── components/          # 组件
│   │   │   ├── ChatMain.vue     # 聊天主区域
│   │   │   ├── ChatSidebar.vue  # 侧边栏
│   │   │   ├── MessageBubble.vue# 消息气泡
│   │   │   ├── WelcomePanel.vue # 欢迎面板
│   │   │   └── ManagementPanel.vue # 管理面板
│   │   ├── view/                # 页面视图
│   │   │   ├── LoginView.vue    # 登录页
│   │   │   └── ChatView.vue     # 聊天页
│   │   ├── stores/              # Pinia 状态管理
│   │   ├── router/              # 路由配置
│   │   ├── types/               # TypeScript 类型
│   │   └── utils/               # 工具函数
│   └── package.json
│
├── backend/                     # 后端项目
│   ├── src/main/java/com/iflytek/chatbot/
│   │   ├── controller/          # REST 控制器
│   │   ├── service/             # 业务逻辑层
│   │   │   ├── ChatService.java # 聊天服务
│   │   │   ├── LongTermMemoryService.java  # 长期记忆
│   │   │   ├── SemanticMemoryService.java  # 语义记忆
│   │   │   ├── SessionService.java         # 会话服务
│   │   │   └── RagService.java             # RAG 服务
│   │   ├── entity/              # JPA 实体
│   │   ├── repository/          # 数据仓库
│   │   ├── dto/                 # 数据传输对象
│   │   ├── plugin/              # 插件系统
│   │   │   ├── impl/            # 内置插件实现
│   │   │   ├── ChatPlugin.java  # 插件接口
│   │   │   └── PluginService.java # 插件服务
│   │   ├── skill/               # Skill 系统
│   │   ├── advisor/             # Spring AI Advisor
│   │   └── config/              # 配置类
│   └── pom.xml
│
└── README.md
```

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- pnpm / npm
- MySQL 8.0+
- Milvus 2.x

### 1. 克隆项目

```bash
git clone <repository-url>
cd chatbot
```

### 2. 配置环境变量

在 `backend` 目录下创建 `.env` 文件：

```env
# DeepSeek API
DEEPSEEK_API_KEY=your_deepseek_api_key

# DashScope (阿里云)
DASHSCOPE_API_KEY=your_dashscope_api_key

# MySQL
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=chatbot
MYSQL_USERNAME=root
MYSQL_PASSWORD=your_password

# Milvus
MILVUS_HOST=localhost
MILVUS_PORT=19530

# 和风天气（天气 Skill）
QWEATHER_API_KEY=your_qweather_api_key
```

### 3. 启动后端

```bash
cd backend

# Maven 构建并运行
./mvnw spring-boot:run

# 或打包后运行
./mvnw clean package -DskipTests
java -jar target/chatbot-0.0.1-SNAPSHOT.jar
```

后端默认启动在 `http://localhost:8080`

API 文档地址：`http://localhost:8080/doc.html` (Knife4j)

### 4. 启动前端

```bash
cd frontend

# 安装依赖
pnpm install

# 开发模式
pnpm dev

# 构建生产版本
pnpm build
```

前端默认启动在 `http://localhost:5173`

## API 接口

### 聊天接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 发送消息 |
| POST | `/api/chat/stream` | 流式发送消息 (SSE) |
| GET | `/api/chat/history` | 获取历史消息 |

### 会话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/sessions` | 创建会话 |
| GET | `/api/sessions` | 获取会话列表 |
| DELETE | `/api/sessions/{id}` | 删除会话 |

### 记忆接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/memory/facts` | 获取用户事实（支持分类筛选） |
| POST | `/api/memory/facts` | 手动创建事实（含语义去重） |
| PUT | `/api/memory/facts/{id}` | 编辑事实 |
| DELETE | `/api/memory/facts/{id}` | 删除事实 |
| GET | `/api/memory/preferences` | 获取用户偏好 |
| PUT | `/api/memory/preferences` | 设置偏好 |
| DELETE | `/api/memory/preferences/{key}` | 删除偏好 |
| GET | `/api/memory/stats` | 获取记忆统计 |
| POST | `/api/memory/extract/{sessionId}` | 手动触发事实提取 |
| POST | `/api/memory/consolidate` | 整合记忆 |

### RAG 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/upload` | 上传文档 |
| GET | `/api/rag/documents` | 获取文档列表 |

### 插件接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/plugins` | 获取插件列表 |

### Skill 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/skills` | 获取 Skill 列表 |
| POST | `/api/skills/{name}/execute` | 执行 Skill |

## 配置说明

### 后端配置 (application.yml)

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
    vectorstore:
      milvus:
        host: ${MILVUS_HOST:localhost}
        port: ${MILVUS_PORT:19530}
  datasource:
    url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}
    username: ${MYSQL_USERNAME}
    password: ${MYSQL_PASSWORD}
```

## 开发指南

### 添加新插件

1. 实现 `ChatPlugin` 接口：

```java
@Component
public class MyPlugin implements ChatPlugin {
    @Override
    public String name() {
        return "my_plugin";
    }

    @Override
    public boolean canHandle(String message) {
        return message.contains("触发词");
    }

    @Override
    public PluginResult execute(PluginContext context) {
        // 插件逻辑
        return PluginResult.of("处理结果", false);
    }
}
```

2. 在 `PluginConfig` 中注册 Bean

### 添加新 Skill

1. 在 `backend/src/main/resources/skills/` 下创建目录
2. 添加 `skill.yaml` 配置文件
3. 实现 Python 脚本

## 许可证

MIT License
