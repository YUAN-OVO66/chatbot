// === 通用 ===
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

// === 聊天 ===
export interface ChatRequest {
  userId: string
  sessionId?: string
  message: string
}

export interface ChatSyncResponse {
  sessionId: string
  reply: string
  retrievedMemoryFacts: string[]
}

export interface ChatDeltaEvent {
  content: string
}

export interface ChatDoneEvent {
  sessionId: string
  reply: string
}

export interface ChatHistoryItem {
  role: 'user' | 'assistant'
  content: string
}

// === 会话 ===
export interface Session {
  id: string
  userId: string
  title: string
  summary: string
  createdAt: string
  updatedAt: string
  isActive: boolean
}

// === 记忆 ===
export interface MemoryFact {
  id: number
  userId: string
  conversationId: string
  factText: string
  category: string
  importance: number
  createdAt: string
}

export interface Preference {
  id: number
  userId: string
  preferenceKey: string
  preferenceValue: string
  confidence: number
  source: string
}

export interface SetPreferenceRequest {
  userId: string
  preferenceKey: string
  preferenceValue: string
}

// === RAG ===
export interface RagDocument {
  id: number
  userId: string
  fileName: string
  fileType: string
  fileSize: number
  status: string
  chunkCount: number
  createdAt: string
}

// === 插件 ===
export interface Plugin {
  name: string
  order: number
  enabled: boolean
}

// === 技能 ===
export interface Skill {
  name: string
  description: string
}

export interface SkillDetail {
  name: string
  description: string
}
