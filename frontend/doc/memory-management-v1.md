# 记忆管理接口文档 v1

> 基础地址: `http://localhost:8099`
> 本文档对应 `frontend-api-doc.md` 第三章"记忆管理"的增强版本。

---

## 概述

记忆管理 v1 在原有基础上新增了以下能力：

| 能力 | 原接口 | v1 新增 |
|------|--------|---------|
| 查看事实列表 | `GET /facts` | 增强：支持 `category` 筛选 |
| 手动创建事实 | - | `POST /facts` |
| 编辑事实 | - | `PUT /facts/{id}` |
| 删除事实 | `DELETE /facts/{id}` | 不变 |
| 查看偏好列表 | `GET /preferences` | 响应字段改为 DTO |
| 设置偏好 | `PUT /preferences` | 响应字段改为 DTO |
| 删除偏好 | - | `DELETE /preferences/{key}` |
| 记忆统计 | - | `GET /stats` |
| 手动触发提取 | - | `POST /extract/{sessionId}` |
| 记忆整合 | `POST /consolidate` | 不变 |

---

## 通用响应格式

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { ... }
}
```

`code` 为 200 表示成功，500 表示失败。

---

## 数据结构

### MemoryFactResponse

```typescript
{
  id: number
  userId: string
  factText: string
  category: string        // "personal_info" | "work" | "habit" | "general"
  importance: number      // 1-10
  createdAt: string       // ISO 8601
  updatedAt: string       // ISO 8601
}
```

### PreferenceResponse

```typescript
{
  id: number
  userId: string
  preferenceKey: string
  preferenceValue: string
  confidence: number      // 0.00-1.00
  source: string          // "explicit" | "extracted"
  createdAt: string       // ISO 8601
  updatedAt: string       // ISO 8601
}
```

### FactCreateRequest

```typescript
{
  userId: string          // 必填
  factText: string        // 必填
  category?: string       // 可选，默认 "general"
  importance?: number     // 可选，默认 5
}
```

---

## 接口列表

### 1. 获取记忆事实列表（增强）

```
GET /api/memory/facts?userId={userId}&category={category}
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `userId` | 是 | 用户 ID |
| `category` | 否 | 分类筛选：`personal_info` / `work` / `habit` / `general` |

不传 `category` 返回全部事实，按重要性降序排列。

**响应:** `Result<MemoryFactResponse[]>`

```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "userId": "user_001",
      "factText": "用户是一名后端工程师",
      "category": "work",
      "importance": 8,
      "createdAt": "2026-06-05T10:00:00",
      "updatedAt": "2026-06-05T10:00:00"
    },
    {
      "id": 2,
      "userId": "user_001",
      "factText": "用户喜欢用 Python",
      "category": "habit",
      "importance": 6,
      "createdAt": "2026-06-05T11:00:00",
      "updatedAt": "2026-06-05T11:00:00"
    }
  ]
}
```

**前端示例:**

```javascript
// 获取全部事实
const res = await fetch('/api/memory/facts?userId=user_001');
const { data: facts } = await res.json();

// 按分类筛选
const res2 = await fetch('/api/memory/facts?userId=user_001&category=work');
const { data: workFacts } = await res2.json();
```

---

### 2. 手动创建记忆事实（新增）

```
POST /api/memory/facts
Content-Type: application/json
```

```json
{
  "userId": "user_001",
  "factText": "用户是一名后端工程师",
  "category": "work",
  "importance": 8
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `userId` | 是 | 用户 ID |
| `factText` | 是 | 事实文本 |
| `category` | 否 | 分类，默认 `general` |
| `importance` | 否 | 重要性 1-10，默认 `5` |

**响应:** `Result<MemoryFactResponse>`

```json
{
  "code": 200,
  "data": {
    "id": 3,
    "userId": "user_001",
    "factText": "用户是一名后端工程师",
    "category": "work",
    "importance": 8,
    "createdAt": "2026-06-05T12:00:00",
    "updatedAt": "2026-06-05T12:00:00"
  }
}
```

**注意:** 接口会自动进行语义去重。若系统中已存在语义相似的事实（向量余弦相似度 >= 0.85），不会重复创建，而是返回已有的事实记录。

**前端示例:**

```javascript
const res = await fetch('/api/memory/facts', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    userId: 'user_001',
    factText: '用户是一名后端工程师',
    category: 'work',
    importance: 8
  })
});
const { data: fact } = await res.json();
```

---

### 3. 编辑记忆事实（新增）

```
PUT /api/memory/facts/{factId}
Content-Type: application/json
```

```json
{
  "userId": "user_001",
  "factText": "用户是一名全栈工程师",
  "category": "work",
  "importance": 9
}
```

所有字段均可选，仅传需要修改的字段。文本变更时会自动重新向量化到 Milvus。

**响应:** `Result<MemoryFactResponse>`

**前端示例:**

```javascript
// 修改重要性
await fetch('/api/memory/facts/3', {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ importance: 9 })
});

// 修改文本
await fetch('/api/memory/facts/3', {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ factText: '用户是一名全栈工程师' })
});
```

---

### 4. 删除记忆事实

```
DELETE /api/memory/facts/{factId}
```

软删除，不会从数据库物理删除。

**响应:** `Result<Void>`

```json
{ "code": 200, "message": "操作成功", "data": null }
```

---

### 5. 获取用户偏好列表（响应增强）

```
GET /api/memory/preferences?userId={userId}
```

**响应:** `Result<PreferenceResponse[]>`

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
      "source": "explicit",
      "createdAt": "2026-06-05T10:00:00",
      "updatedAt": "2026-06-05T10:00:00"
    },
    {
      "id": 2,
      "userId": "user_001",
      "preferenceKey": "theme",
      "preferenceValue": "dark",
      "confidence": 0.50,
      "source": "extracted",
      "createdAt": "2026-06-05T11:00:00",
      "updatedAt": "2026-06-05T11:00:00"
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `confidence` | 置信度 0.00-1.00。手动设置 = 1.00，LLM 提取 = 0.50 |
| `source` | `explicit` = 用户手动设置，`extracted` = LLM 自动提取 |

---

### 6. 设置用户偏好（响应增强）

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

若 key 已存在则更新值，置信度自动设为 1.0，来源标记为 `explicit`。

**响应:** `Result<PreferenceResponse>`

---

### 7. 删除用户偏好（新增）

```
DELETE /api/memory/preferences/{preferenceKey}?userId={userId}
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `userId` | 是 | 用户 ID（query 参数） |
| `preferenceKey` | 是 | 偏好键（路径参数，URL 编码） |

**响应:** `Result<Void>`

**前端示例:**

```javascript
const key = encodeURIComponent('language');
await fetch(`/api/memory/preferences/${key}?userId=user_001`, {
  method: 'DELETE'
});
```

---

### 8. 记忆统计（新增）

```
GET /api/memory/stats?userId={userId}
```

**响应:**

```json
{
  "code": 200,
  "data": {
    "totalFacts": 12,
    "totalPreferences": 3
  }
}
```

**前端示例:**

```javascript
const res = await fetch('/api/memory/stats?userId=user_001');
const { data: stats } = await res.json();
console.log(`事实: ${stats.totalFacts} 条, 偏好: ${stats.totalPreferences} 条`);
```

---

### 9. 手动触发事实提取（新增）

```
POST /api/memory/extract/{sessionId}?userId={userId}
```

从指定会话的历史消息中提取用户事实和偏好。适用于想立即提取记忆而不等待自动触发（每 2 轮对话自动触发一次）的场景。

| 参数 | 必填 | 说明 |
|------|------|------|
| `sessionId` | 是 | 会话 ID（路径参数） |
| `userId` | 是 | 用户 ID（query 参数） |

**响应:**

```json
{
  "code": 200,
  "message": "提取完成，会话消息数: 8",
  "data": null
}
```

**前端示例:**

```javascript
const res = await fetch(`/api/memory/extract/${sessionId}?userId=user_001`, {
  method: 'POST'
});
const { message } = await res.json();
```

---

### 10. 触发记忆整合

```
POST /api/memory/consolidate?userId={userId}
```

去除重复和矛盾的记忆事实。正常流程中提取后会自动整合，此接口用于手动触发清理。

**响应:** `Result<Void>`

---

## 前端页面建议

### 记忆管理页

```
┌─────────────────────────────────────────────┐
│  记忆管理                        [整合记忆]   │
├─────────────────────────────────────────────┤
│  统计: 12 条事实 · 3 条偏好                   │
├─────────────────────────────────────────────┤
│  事实列表              [+ 新增事实]           │
│  ┌─────────────────────────────────────────┐ │
│  │ [全部] [work] [habit] [personal_info]   │ │
│  ├─────────────────────────────────────────┤ │
│  │ ⭐⭐⭐⭐⭐⭐⭐⭐ 用户是一名后端工程师 [编辑][删除]│ │
│  │ ⭐⭐⭐⭐⭐⭐     用户喜欢用 Python    [编辑][删除]│ │
│  │ ⭐⭐⭐⭐⭐       用户在北京工作        [编辑][删除]│ │
│  └─────────────────────────────────────────┘ │
├─────────────────────────────────────────────┤
│  偏好列表              [+ 新增偏好]           │
│  ┌─────────────────────────────────────────┐ │
│  │ language = Python  (1.00, 手动)    [删除]│ │
│  │ theme    = dark    (0.50, 自动)    [删除]│ │
│  └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 调用时序

```
页面加载:
  1. GET /api/memory/stats            → 显示统计
  2. GET /api/memory/facts            → 填充事实列表
  3. GET /api/memory/preferences      → 填充偏好列表

新增事实:
  POST /api/memory/facts              → 刷新列表 + 统计

编辑事实:
  PUT /api/memory/facts/{id}          → 刷新列表

删除事实:
  DELETE /api/memory/facts/{id}       → 刷新列表 + 统计

分类筛选:
  GET /api/memory/facts?category=work → 刷新事实列表

手动提取:
  POST /api/memory/extract/{sessionId} → 提示结果 → 刷新列表

整合记忆:
  POST /api/memory/consolidate         → 刷新列表 + 统计
```
