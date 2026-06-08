import { get, put, del, post } from './request'
import type {
  MemoryFact,
  FactCreateRequest,
  FactUpdateRequest,
  Preference,
  SetPreferenceRequest,
  MemoryStats,
  ApiResponse,
} from '@/types'

// 获取记忆事实（支持分类筛选）
export function getMemoryFacts(userId: string, category?: string): Promise<ApiResponse<MemoryFact[]>> {
  const params: Record<string, string> = { userId }
  if (category) params.category = category
  return get<MemoryFact[]>('/memory/facts', params)
}

// 创建记忆事实
export function createMemoryFact(params: FactCreateRequest): Promise<ApiResponse<MemoryFact>> {
  return post<MemoryFact>('/memory/facts', params)
}

// 编辑记忆事实
export function updateMemoryFact(factId: number, params: FactUpdateRequest): Promise<ApiResponse<MemoryFact>> {
  return put<MemoryFact>(`/memory/facts/${factId}`, params)
}

// 删除记忆事实
export function deleteMemoryFact(factId: number): Promise<ApiResponse<unknown>> {
  return del<unknown>(`/memory/facts/${factId}`)
}

// 获取用户偏好
export function getPreferences(userId: string): Promise<ApiResponse<Preference[]>> {
  return get<Preference[]>('/memory/preferences', { userId })
}

// 设置用户偏好
export function setPreference(params: SetPreferenceRequest): Promise<ApiResponse<Preference>> {
  return put<Preference>('/memory/preferences', params)
}

// 删除用户偏好
export function deletePreference(preferenceKey: string, userId: string): Promise<ApiResponse<unknown>> {
  return del<unknown>(`/memory/preferences/${encodeURIComponent(preferenceKey)}?userId=${encodeURIComponent(userId)}`)
}

// 获取记忆统计
export function getMemoryStats(userId: string): Promise<ApiResponse<MemoryStats>> {
  return get<MemoryStats>('/memory/stats', { userId })
}

// 手动触发事实提取
export function extractMemory(sessionId: string, userId: string): Promise<ApiResponse<unknown>> {
  return post<unknown>(`/memory/extract/${sessionId}?userId=${encodeURIComponent(userId)}`)
}

// 触发记忆整合
export function consolidateMemory(userId: string): Promise<ApiResponse<unknown>> {
  return post<unknown>(`/memory/consolidate?userId=${encodeURIComponent(userId)}`)
}

// 重置所有记忆（事实 + 偏好 + 向量）
export function resetMemory(userId: string): Promise<ApiResponse<unknown>> {
  return del<unknown>(`/memory/reset?userId=${encodeURIComponent(userId)}`)
}
