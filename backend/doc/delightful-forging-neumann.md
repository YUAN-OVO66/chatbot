# RAG 知识库功能实现计划

## Context

项目是一个 Spring Boot 3.5.14 聊天机器人后端，已具备三层记忆体系（短期/语义/长期）和双 Milvus 向量存储配置。`RagController` 和 `ragVectorStore` bean 已存在但未使用。需要实现 RAG 知识库功能：文档上传 → 分块 → 向量化 → 检索增强生成。

## 实现步骤

### 1. 添加 PDF 读取依赖
**文件:** `pom.xml`
- 添加 `spring-ai-pdf-document-reader:1.1.0`（引入 pdfbox，是唯一需要新增的依赖）

### 2. 数据库表
**文件:** `src/main/resources/schema.sql`
- 追加 `rag_document` 表（id, user_id, file_name, file_type, file_size, status, chunk_count, error_message, timestamps）

### 3. 新建实体和仓库
- `entity/RagDocument.java` — JPA 实体（@Data, @PrePersist/@PreUpdate）
- `repository/RagDocumentRepository.java` — JpaRepository，按 userId 查询

### 4. 新建 DTO
- `dto/RagDocumentResponse.java` — record 类型响应

### 5. 核心服务 RagService
**文件:** `service/RagService.java`
- `uploadDocument(userId, file)` — 验证 → 读取文件 → TokenTextSplitter 分块(500 token) → 添加元数据(userId, documentId, type=rag) → ragVectorStore 存储 → 更新 MySQL 状态
- `listDocuments(userId)` — 查询用户文档列表
- `deleteDocument(userId, documentId)` — 删除 Milvus 向量 + MySQL 记录
- `searchRelevantChunks(userId, query, topK)` — 语义检索，供 RagAdvisor 调用

### 6. REST 接口
**文件:** `controller/RagController.java`（替换空占位类）
- `POST /api/rag/upload` — 上传文档（multipart）
- `GET /api/rag/documents?userId=` — 文档列表
- `DELETE /api/rag/documents/{documentId}?userId=` — 删除文档

### 7. RAG Advisor
**文件:** `advisor/RagAdvisor.java`
- 自定义 Advisor（order=3），复用 LongTermMemoryAdvisor 模式
- 在 before() 中检索相关文本块，注入 SystemMessage 的 "Relevant Knowledge:" 段

### 8. 接入 Advisor 链
**文件:** `config/MemoryConfig.java`
- chatClient bean 注入 RagAdvisor，添加到 defaultAdvisors（order=3，在长期记忆之后、日志之前）

## Advisor 执行顺序（更新后）
| Order | Advisor | 用途 |
|-------|---------|------|
| 0 | MessageChatMemoryAdvisor | 短期记忆 |
| 1 | VectorStoreChatMemoryAdvisor | 语义记忆 |
| 2 | LongTermMemoryAdvisor | 长期记忆 |
| **3** | **RagAdvisor（新增）** | **RAG 知识检索** |
| 100 | SimpleLoggerAdvisor | 日志 |

## 验证方式
1. `mvn compile` 编译通过
2. 启动应用，`rag_document` 表自动创建
3. 通过 Knife4j (`/doc.html`) 测试上传 TXT/PDF/MD 文件
4. 测试文档列表和删除接口
5. 在聊天中提问与上传文档相关的问题，验证 RAG 上下文注入
