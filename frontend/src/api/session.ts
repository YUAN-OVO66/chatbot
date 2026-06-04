import { get, post, del } from './request'
import type { Session, Page, ApiResponse } from '@/types'

// 创建会话
export function createSession(userId: string): Promise<ApiResponse<Session>> {
  return post<Session>('/sessions', { userId })
}

// 获取会话列表（分页）
export function getSessions(userId: string, page = 0, size = 20): Promise<ApiResponse<Page<Session>>> {
  return get<Page<Session>>('/sessions', { userId, page, size })
}

// 获取单个会话
export function getSession(sessionId: string): Promise<ApiResponse<Session>> {
  return get<Session>(`/sessions/${sessionId}`)
}

// 删除会话
export function deleteSession(sessionId: string): Promise<ApiResponse<unknown>> {
  return del<unknown>(`/sessions/${sessionId}`)
}
