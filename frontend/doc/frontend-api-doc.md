# Chatbot 前端接口文档

> 基础地址: `http://localhost:8099`
> Swagger UI: `http://localhost:8099/swagger-ui.html`
> Knife4j UI: `http://localhost:8099/doc.html`
> 无认证、无 CORS 限制

---

## 通用响应格式

所有非流式接口均返回以下包装结构：

```json
{
  "code": 200,        // 200=成功, 500=失败
  "message": "操作成功",
  "data": { ... }     // 具体业务数据
}
```

---

## 一、聊天接口 `/api/chat`

### 1.1 发送消息（同步）

```
POST /api/chat
Content-Type: application/json
```

**请求体:**
```json
{
  "userId": "user_001",
  "sessionId": "xxx-xxx",   // 可选，首次为空时服务端自动创建
  "message": "你好"
}
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "sessionId": "xxx-xxx",
    "reply": "你好！有什么可以帮你的？",
    "retrievedMemoryFacts": []
  }
}
```

### 1.2 发送消息（流式 SSE） -- 推荐

```
POST /api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

**请求体:** 同上

**SSE 事件格式:**

```
event: delta
data: {"content": "你"}

event: delta
data: {"content": "好"}

event: delta
data: {"content": "！"}

event: done
data: {"sessionId": "xxx-xxx", "reply": "你好！"}
```

| 事件名 | 说明 | data 字段 |
|--------|------|-----------|
| `delta` | LLM 逐 token 生成的片段 | `content`: string |
| `done` | 流结束，携带完整回复 | `sessionId`: string, `reply`: string |
| `error` | 发生错误 | `message`: string |

**前端调用示例:**

```javascript
const response = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ userId: 'user_001', sessionId: '', message: '你好' })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();
let buffer = '';

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  buffer += decoder.decode(value, { stream: true });
  const lines = buffer.split('\n');
  buffer = lines.pop(); // 保留不完整的行

  let eventType = '';
  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventType = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      const data = JSON.parse(line.slice(5).trim());
      if (eventType === 'delta') {
        // 追加 token 到 UI
        appendToChat(data.content);
      } else if (eventType === 'done') {
        // 流结束，data.sessionId 和 data.reply 可用
        onStreamDone(data);
      } else if (eventType === 'error') {
        onError(data.message);
      }
    }
  }
}
```

### 1.3 获取历史消息

```
GET /api/chat/history?sessionId=xxx-xxx
```

**响应:**
```json
{
  "code": 200,
  "data": [
    { "role": "user", "content": "你好" },
    { "role": "assistant", "content": "你好！" }
  ]
}
```

---

## 二、会话管理 `/api/sessions`

### 2.1 创建会话

```
POST /api/sessions
Content-Type: application/json
```

```json
{ "userId": "user_001" }
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "id": "uuid-xxx",
    "userId": "user_001",
    "title": "",
    "summary": "",
    "createdAt": "2026-06-03T10:00:00",
    "updatedAt": "2026-06-03T10:00:00",
    "isActive": true
  }
}
```

### 2.2 会话列表（分页）

```
GET /api/sessions?userId=user_001&page=0&size=20
```

**响应:**
```json
{
  "code": 200,
  "data": {
    "content": [ /* SessionResponse[] */ ],
    "totalElements": 50,
    "totalPages": 3,
    "number": 0,
    "size": 20
  }
}
```

### 2.3 获取单个会话

```
GET /api/sessions/{sessionId}
```

### 2.4 删除会话（归档）

```
DELETE /api/sessions/{sessionId}
```

---

## 三、记忆管理 `/api/memory`

### 3.1 获取记忆事实

```
GET /api/memory/facts?userId=user_001
```

**响应:**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "userId": "user_001",
      "conversationId": "xxx",
      "factText": "用户喜欢用 Python",
      "category": "general",
      "importance": 5,
      "createdAt": "2026-06-03T10:00:00"
    }
  ]
}
```

### 3.2 删除记忆事实

```
DELETE /api/memory/facts/{factId}
```

### 3.3 获取用户偏好

```
GET /api/memory/preferences?userId=user_001
```

**响应:**
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "userId": "user_001",
      "preferenceKey": "language",
      "preferenceValue": "Python",
      "confidence": 1.00,
      "source": "explicit"
    }
  ]
}
```

### 3.4 设置用户偏好

```
PUT /api/memory/preferences
Content-Type: application/json
```

```json
{
  "userId": "user_001",
  "preferenceKey": "language",
  "preferenceValue": "Python"
}
```

### 3.5 触发记忆整合

```
POST /api/memory/consolidate?userId=user_001
```

---

## 四、知识库（RAG） `/api/rag`

### 4.1 上传文档

```
POST /api/rag/upload
Content-Type: multipart/form-data
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `userId` | text | 用户 ID |
| `file` | file | 支持 PDF / TXT / MD |

**响应:**
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "userId": "user_001",
    "fileName": "文档.pdf",
    "fileType": "pdf",
    "fileSize": 102400,
    "status": "completed",
    "chunkCount": 15,
    "createdAt": "2026-06-03T10:00:00"
  }
}
```

### 4.2 文档列表

```
GET /api/rag/documents?userId=user_001
```

**响应:** `Result<List<RagDocumentResponse>>`

### 4.3 删除文档

```
DELETE /api/rag/documents/{documentId}?userId=user_001
```

---

## 五、插件管理 `/api/plugins`

### 5.1 插件列表

```
GET /api/plugins
```

**响应:**
```json
{
  "code": 200,
  "data": [
    { "name": "time", "order": 10, "enabled": false },
    { "name": "calculator", "order": 20, "enabled": false },
    { "name": "web-search", "order": 50, "enabled": false }
  ]
}
```

### 5.2 启用插件

```
PUT /api/plugins/{name}/enable
```

### 5.3 禁用插件

```
PUT /api/plugins/{name}/disable
```

---

## 六、技能管理 `/api/skills`

### 6.1 技能列表

```
GET /api/skills
```

**响应:**
```json
{
  "code": 200,
  "data": [
    { "name": "email", "description": "邮件助手..." },
    { "name": "weather", "description": "天气查询..." },
    { "name": "devops", "description": "运维助手..." },
    { "name": "sql", "description": "SQL 助手..." },
    { "name": "translator", "description": "翻译助手..." },
    { "name": "summary", "description": "文本摘要助手..." }
  ]
}
```

### 6.2 获取技能详情

```
GET /api/skills/{name}
```

### 6.3 获取技能内容

```
GET /api/skills/{name}/content
```

**响应:** `Result<String>` -- 返回 SKILL.md 的 Markdown 文本

### 6.4 热重载技能

```
POST /api/skills/reload
```

---

## 附录：业务流程参考

### 聊天主流程

```
用户发送消息
  → POST /api/chat/stream（推荐，流式）
  ← SSE 事件流（delta 逐 token + done 完成）
  → GET /api/chat/history（刷新聊天记录）
```

### 首次使用

```
1. POST /api/sessions           -- 创建会话
2. POST /api/chat/stream        -- 开始聊天
3. (可选) POST /api/rag/upload  -- 上传知识库文档
4. (可选) PUT /api/memory/preferences -- 设置偏好
```

### 管理功能

```
GET  /api/plugins               -- 查看插件
PUT  /api/plugins/{name}/enable -- 启用插件
GET  /api/skills                -- 查看技能
POST /api/skills/reload         -- 重载技能
```
