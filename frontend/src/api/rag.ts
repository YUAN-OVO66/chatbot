import { get, del } from './request'
import type { RagDocument, ApiResponse } from '@/types'
import axios from 'axios'

// 上传文档（multipart/form-data）
export function uploadDocument(userId: string, file: File): Promise<ApiResponse<RagDocument>> {
  const formData = new FormData()
  formData.append('userId', userId)
  formData.append('file', file)
  return axios.post<ApiResponse<RagDocument>>('/api/rag/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((res) => res.data)
}

// 获取文档列表
export function getDocuments(userId: string): Promise<ApiResponse<RagDocument[]>> {
  return get<RagDocument[]>('/rag/documents', { userId })
}

// 删除文档
export function deleteDocument(documentId: number, userId: string): Promise<ApiResponse<unknown>> {
  return del<unknown>(`/rag/documents/${documentId}?userId=${userId}`)
}
