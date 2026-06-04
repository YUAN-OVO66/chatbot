import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'

const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 请求拦截器
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一错误处理
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return response
  },
  (error) => {
    const status = error.response?.status
    const messages: Record<number, string> = {
      400: '请求参数错误',
      403: '拒绝访问',
      404: '请求地址不存在',
      500: '服务器内部错误',
    }
    ElMessage.error(messages[status] || error.message || '网络异常')
    return Promise.reject(error)
  }
)

// 封装常用方法
export function get<T>(url: string, params?: object): Promise<ApiResponse<T>> {
  return service.get(url, { params }).then((res) => res.data)
}

export function post<T>(url: string, data?: object): Promise<ApiResponse<T>> {
  return service.post(url, data).then((res) => res.data)
}

export function put<T>(url: string, data?: object): Promise<ApiResponse<T>> {
  return service.put(url, data).then((res) => res.data)
}

export function del<T>(url: string): Promise<ApiResponse<T>> {
  return service.delete(url).then((res) => res.data)
}

// SSE 流式请求（用于 chat 流式输出）
export function postStream(
  url: string,
  data: object,
  onDelta: (content: string) => void,
  onDone?: (sessionId: string, reply: string) => void,
  onError?: (message: string) => void
): AbortController {
  const controller = new AbortController()

  fetch(`/api${url}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
    },
    body: JSON.stringify(data),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const reader = response.body?.getReader()
      if (!reader) return
      const decoder = new TextDecoder()
      let buffer = ''
      let eventType = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim()
            if (!dataStr) continue
            try {
              const parsed = JSON.parse(dataStr)
              if (eventType === 'delta') {
                onDelta(parsed.content)
              } else if (eventType === 'done') {
                onDone?.(parsed.sessionId, parsed.reply)
              } else if (eventType === 'error') {
                onError?.(parsed.message)
              }
            } catch {
              // 忽略解析错误
            }
            eventType = ''
          }
        }
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onError?.(err.message || '请求异常')
      }
    })

  return controller
}

export default service
