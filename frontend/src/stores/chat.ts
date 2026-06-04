import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getSessions, createSession as apiCreateSession, deleteSession as apiDeleteSession } from '@/api/session'
import { getHistory, sendMessageStream } from '@/api/chat'
import { useUserStore } from './user'
import type { Session, ChatHistoryItem } from '@/types'

export interface Message {
  role: 'user' | 'assistant'
  content: string
  loading?: boolean
  placement: 'start' | 'end'
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<string | null>(null)
  const messages = ref<Message[]>([])
  const loading = ref(false)
  const sessionsLoading = ref(false)
  const sessionsPage = ref(0)
  const sessionsTotal = ref(0)
  let streamController: AbortController | null = null

  const currentSession = computed(() =>
    sessions.value.find((s: Session) => s.id === currentSessionId.value) || null
  )

  // 获取会话列表
  async function fetchSessions(page = 0, size = 20) {
    const userStore = useUserStore()
    if (!userStore.userId) return
    sessionsLoading.value = true
    try {
      const res = await getSessions(userStore.userId, page, size)
      const pageData = res.data
      if (page === 0) {
        sessions.value = pageData.content
      } else {
        sessions.value.push(...pageData.content)
      }
      sessionsPage.value = pageData.number
      sessionsTotal.value = pageData.totalElements
    } finally {
      sessionsLoading.value = false
    }
  }

  // 创建新会话
  async function createSession() {
    const userStore = useUserStore()
    if (!userStore.userId) return null
    const res = await apiCreateSession(userStore.userId)
    const session = res.data
    sessions.value.unshift(session)
    currentSessionId.value = session.id
    messages.value = []
    return session
  }

  // 切换会话
  async function switchSession(sessionId: string) {
    if (currentSessionId.value === sessionId) return
    currentSessionId.value = sessionId
    messages.value = []
    await loadHistory(sessionId)
  }

  // 加载历史消息
  async function loadHistory(sessionId: string) {
    const res = await getHistory(sessionId)
    messages.value = res.data.map((item: ChatHistoryItem) => ({
      role: item.role,
      content: item.content,
      placement: item.role === 'user' ? 'end' : 'start',
    }))
  }

  // 删除会话
  async function deleteSession(sessionId: string) {
    await apiDeleteSession(sessionId)
    sessions.value = sessions.value.filter((s: Session) => s.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      if (sessions.value.length > 0) {
        await switchSession(sessions.value[0].id)
      } else {
        currentSessionId.value = null
        messages.value = []
      }
    }
  }

  // 发送消息（流式）
  async function sendMessage(content: string) {
    const userStore = useUserStore()
    if (!userStore.userId) return

    // 如果没有当前会话，先创建
    if (!currentSessionId.value) {
      const session = await createSession()
      if (!session) return
    }

    // 追加用户消息
    messages.value.push({ role: 'user', content, placement: 'end' })

    // 追加空的 assistant 消息（loading 始终为 false，保持内容插槽可见）
    messages.value.push({ role: 'assistant', content: '', loading: false, placement: 'start' })
    const msgIndex = messages.value.length - 1

    loading.value = true

    streamController = sendMessageStream(
      {
        userId: userStore.userId,
        sessionId: currentSessionId.value!,
        message: content,
      },
      // onDelta
      (delta: string) => {
        messages.value[msgIndex].content += delta
      },
      // onDone
      (sessionId: string, reply: string) => {
        messages.value[msgIndex].content = reply
        loading.value = false
        // 如果是新会话，更新 sessionId
        if (!currentSessionId.value) {
          currentSessionId.value = sessionId
        }
        // 更新会话列表中的标题（如果是第一条消息）
        const session = sessions.value.find((s: Session) => s.id === sessionId)
        if (session && !session.title) {
          session.title = content.slice(0, 20)
        }
      },
      // onError
      (message: string) => {
        messages.value[msgIndex].content += `\n\n[错误: ${message}]`
        loading.value = false
      }
    )
  }

  // 停止生成
  function stopGeneration() {
    streamController?.abort()
    streamController = null
    loading.value = false
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    messages,
    loading,
    sessionsLoading,
    sessionsPage,
    sessionsTotal,
    fetchSessions,
    createSession,
    switchSession,
    deleteSession,
    sendMessage,
    stopGeneration,
  }
})
