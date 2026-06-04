import { get, put } from './request'
import type { Plugin, ApiResponse } from '@/types'

// 获取插件列表
export function getPlugins(): Promise<ApiResponse<Plugin[]>> {
  return get<Plugin[]>('/plugins')
}

// 启用插件
export function enablePlugin(name: string): Promise<ApiResponse<unknown>> {
  return put<unknown>(`/plugins/${name}/enable`)
}

// 禁用插件
export function disablePlugin(name: string): Promise<ApiResponse<unknown>> {
  return put<unknown>(`/plugins/${name}/disable`)
}
