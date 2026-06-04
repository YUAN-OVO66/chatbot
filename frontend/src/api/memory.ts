import { get, put, del, post } from './request'
import type { MemoryFact, Preference, SetPreferenceRequest, ApiResponse } from '@/types'

// 获取记忆事实
export function getMemoryFacts(userId: string): Promise<ApiResponse<MemoryFact[]>> {
  return get<MemoryFact[]>('/memory/facts', { userId })
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
export function setPreference(params: SetPreferenceRequest): Promise<ApiResponse<unknown>> {
  return put<unknown>('/memory/preferences', params)
}

// 触发记忆整合
export function consolidateMemory(userId: string): Promise<ApiResponse<unknown>> {
  return post<unknown>(`/memory/consolidate`, { userId })
}
