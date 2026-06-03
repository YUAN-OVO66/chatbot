# 聊天长期记忆模块设计文档

## 1. 架构概览

记忆系统分为三层，每层职责明确，使用不同的存储机制。

```
用户查询
  |
  v
[第一层] 短期记忆（Spring AI 内置）
  -> MessageChatMemoryAdvisor，滑动窗口保留最近 N 条消息
  -> 由 SPRING_AI_CHAT_MEMORY 表支撑（MySQL，自动管理）
  |
  v
[第二层] 语义记忆（向量检索）
  -> VectorStoreChatMemoryAdvisor，通过 DashScope 做文本嵌入
  -> 在 Milvus 中检索 Top-K 相似历史对话
  -> 注入系统提示词
  |
  v
[第三层] 长期记忆（提取的事实）
  -> 自定义 LongTermMemoryAdvisor
  -> 从 Milvus 检索相关事实/偏好
  -> 注入系统提示词
  |
  v
DeepSeek 聊天模型 -> 回复
  |
  v
[回复后] 异步记忆提取
  -> LLM 分析对话，提取事实 + 偏好
  -> 存入 MySQL + 向量化存入 Milvus
```

### Advisor 链顺序

| 顺序 | Advisor | 职责 |
|------|---------|------|
| 0 | MessageChatMemoryAdvisor | 注入近期对话消息（短期记忆） |
| 1 | VectorStoreChatMemoryAdvisor | 检索语义相似的历史对话 |
| 2 | LongTermMemoryAdvisor | 检索相关事实和偏好 |
| 100 | SimpleLoggerAdvisor | 日志记录 |

## 2. 数据库表结构

### 2.1 SPRING_AI_CHAT_MEMORY（Spring AI 自动管理）

```sql
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    `conversation_id` VARCHAR(36) NOT NULL,
    `content`         TEXT NOT NULL,
    `type`            ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
    `timestamp`       TIMESTAMP NOT NULL,
    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.2 chat_session（会话表）

```sql
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`              VARCHAR(36)   NOT NULL PRIMARY KEY COMMENT 'UUID，与 conversation_id 一致',
    `user_id`         VARCHAR(64)   NOT NULL COMMENT '外部用户标识',
    `title`           VARCHAR(255)  DEFAULT NULL COMMENT '自动生成的会话标题',
    `summary`         TEXT          DEFAULT NULL COMMENT 'LLM 生成的会话摘要',
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1=活跃, 0=已归档',
    INDEX `idx_chat_session_user_id` (`user_id`),
    INDEX `idx_chat_session_user_active` (`user_id`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.3 user_memory_facts（用户记忆事实表）

```sql
CREATE TABLE IF NOT EXISTS `user_memory_facts` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`         VARCHAR(64)   NOT NULL,
    `conversation_id` VARCHAR(36)   DEFAULT NULL,
    `fact_text`       TEXT          NOT NULL,
    `category`        VARCHAR(64)   DEFAULT 'general',
    `importance`      TINYINT       NOT NULL DEFAULT 5,
    `document_id`     VARCHAR(128)  DEFAULT NULL,
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_active`       TINYINT(1)    NOT NULL DEFAULT 1,
    INDEX `idx_memory_facts_user_id` (`user_id`),
    INDEX `idx_memory_facts_user_category` (`user_id`, `category`),
    INDEX `idx_memory_facts_user_active` (`user_id`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.4 user_preferences（用户偏好表）

```sql
CREATE TABLE IF NOT EXISTS `user_preferences` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`         VARCHAR(64)   NOT NULL,
    `preference_key`  VARCHAR(128)  NOT NULL,
    `preference_value` TEXT         NOT NULL,
    `confidence`      DECIMAL(3,2)  NOT NULL DEFAULT 0.50,
    `source`          VARCHAR(64)   DEFAULT 'extracted',
    `document_id`     VARCHAR(128)  DEFAULT NULL,
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX `idx_user_pref_key` (`user_id`, `preference_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.5 memory_extraction_log（记忆提取日志表）

```sql
CREATE TABLE IF NOT EXISTS `memory_extraction_log` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` VARCHAR(36)   NOT NULL,
    `user_id`         VARCHAR(64)   NOT NULL,
    `extraction_type` VARCHAR(32)   NOT NULL,
    `input_message_count` INT       DEFAULT NULL,
    `extracted_count` INT           DEFAULT NULL,
    `status`          VARCHAR(16)   NOT NULL DEFAULT 'success',
    `error_message`   TEXT          DEFAULT NULL,
    `created_at`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_extraction_log_user` (`user_id`),
    INDEX `idx_extraction_log_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## 3. 包结构

```
com.iflytek.chatbot/
  config/
    MemoryConfig.java          -- ChatMemory、ChatClient Bean 及 Advisor 链配置
    AsyncConfig.java           -- @EnableAsync，支持异步记忆提取
    DotenvEnvironmentPostProcessor.java -- 加载 .env 环境变量
  entity/
    ChatSession.java           -- chat_session 表 JPA 实体
    UserMemoryFact.java        -- user_memory_facts 表 JPA 实体
    UserPreference.java        -- user_preferences 表 JPA 实体
    MemoryExtractionLog.java   -- memory_extraction_log 表 JPA 实体
  dto/
    ChatRequest.java           -- 请求 DTO：sessionId, userId, message
    ChatResponse.java          -- 响应 DTO：sessionId, reply, retrievedMemoryFacts
    SessionCreateRequest.java  -- 创建会话请求 DTO
    SessionResponse.java       -- 会话元数据 DTO
    PreferenceRequest.java     -- 设置偏好请求 DTO
    Result.java                -- 统一响应包装类
  repository/
    ChatSessionRepository.java
    UserMemoryFactRepository.java
    UserPreferenceRepository.java
    MemoryExtractionLogRepository.java
  service/
    ChatService.java           -- 聊天编排：调用带记忆的 ChatClient
    SessionService.java        -- 会话 CRUD 及生命周期管理
    LongTermMemoryService.java -- 事实提取、检索、记忆整合
    SemanticMemoryService.java -- 管理对话向量存储
  advisor/
    LongTermMemoryAdvisor.java -- 自定义 Advisor，检索事实注入提示词
  controller/
    ChatController.java        -- POST /api/chat
    SessionController.java     -- /api/sessions 会话管理
    MemoryController.java      -- /api/memory 记忆管理
```

## 4. API 接口

### 4.1 聊天

```
POST /api/chat
请求体: { "userId": "user1", "sessionId": "uuid或null", "message": "你好" }
响应体: { "code": 200, "message": "success", "data": { "sessionId": "uuid", "reply": "你好！", "retrievedMemoryFacts": [] } }
```

### 4.2 会话管理

```
POST   /api/sessions                    -- 创建新会话
GET    /api/sessions?userId=user1       -- 获取用户会话列表
GET    /api/sessions/{sessionId}        -- 获取会话详情
DELETE /api/sessions/{sessionId}        -- 归档会话
```

### 4.3 记忆管理

```
GET    /api/memory/facts?userId=user1           -- 获取所有事实
DELETE /api/memory/facts/{factId}                -- 软删除事实
GET    /api/memory/preferences?userId=user1      -- 获取偏好列表
PUT    /api/memory/preferences                    -- 设置偏好（显式）
POST   /api/memory/consolidate?userId=user1       -- 触发记忆整合
```

## 5. 关键设计决策

### 5.1 会话 ID = 对话 ID

`chat_session.id`（UUID）直接作为 `SPRING_AI_CHAT_MEMORY` 中的 `conversation_id`，无需额外映射层。每次请求通过 Advisor 上下文 Map 传入，键为 `"chat_memory_conversation_id"`。

### 5.2 事实双存储

- **MySQL**：存储结构化的权威事实，包含元数据（分类、重要性、时间戳）
- **Milvus**：存储向量嵌入用于语义检索，通过 `documentId` 关联

先通过 Milvus 语义搜索，再回查 MySQL 获取完整记录。

### 5.3 异步提取

记忆提取通过 `@Async` 在后台运行，避免增加聊天响应延迟。提取日志表提供审计追踪能力。

### 5.4 JPA + JDBC 共存

- Spring AI 的 `JdbcChatMemoryRepository` 通过原生 JDBC 管理 `SPRING_AI_CHAT_MEMORY`
- 自定义表使用 JPA 进行实体生命周期管理和查询派生
- 两者共享同一个 `DataSource` 和事务管理器

## 6. Milvus 文档结构

### 对话片段

```
文本: "[User]: {消息内容}\n[Assistant]: {回复内容}"
元数据: { userId, conversationId, type: "conversation", timestamp }
向量: 1536 维（DashScope text-embedding-v3）
```

### 提取的事实

```
文本: 自然语言描述的事实
元数据: { userId, type: "fact", category, importance, factId }
向量: 1536 维（DashScope text-embedding-v3）
```

## 7. 配置说明

### application.yml（关键配置）

```yaml
spring:
  datasource:
    url: jdbc:mysql://host:port/chatbot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: root
    password: ${MYSQL_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql

  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always
            platform: mysql
```

### pom.xml（关键依赖）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-milvus</artifactId>
</dependency>
```

## 8. 使用示例

### 创建会话并聊天

```bash
# 创建会话
curl -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1"}'

# 聊天（首次消息 - 无需 sessionId，会自动创建）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "message": "我叫张三，在阿里巴巴工作"}'

# 聊天（继续对话 - 使用返回的 sessionId）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "sessionId": "返回的uuid", "message": "我喜欢简洁的回复"}'

# 查看提取的事实
curl http://localhost:8080/api/memory/facts?userId=user1

# 查看偏好
curl http://localhost:8080/api/memory/preferences?userId=user1
```

### 新会话中的记忆检索

```bash
# 新建会话 - 机器人应该仍记得之前对话中的事实
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "message": "你对我了解多少？"}'
```

系统处理流程：
1. 创建新会话（短期记忆为空）
2. 通过 DashScope 将查询文本向量化
3. 在 Milvus 中检索相关的历史对话和事实
4. 将检索到的上下文注入系统提示词
5. DeepSeek 基于记忆上下文生成回复
