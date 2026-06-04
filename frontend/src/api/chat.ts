import { get, post, postStream } from './request'
import type { ChatHistoryItem, ChatRequest, ChatSyncResponse, ApiResponse } from '@/types'

// 同步发送消息
export function sendMessage(params: ChatRequest): Promise<ApiResponse<ChatSyncResponse>> {
  return post<ChatSyncResponse>('/chat', params)
}

// 流式发送消息（SSE）
export function sendMessageStream(
  params: ChatRequest,
  onDelta: (content: string) => void,
  onDone?: (sessionId: string, reply: string) => void,
  onError?: (message: string) => void
): AbortController {
  return postStream('/chat/stream', params, onDelta, onDone, onError)
}

// 获取历史消息
export function getHistory(sessionId: string): Promise<ApiResponse<ChatHistoryItem[]>> {
  return get<ChatHistoryItem[]>('/chat/history', { sessionId })
}
