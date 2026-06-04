<script setup lang="ts">
import { ref, computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { ElMessage } from 'element-plus'
import { BubbleList, XSender } from 'vue-element-plus-x'
import { MarkdownRenderer } from 'x-markdown-vue'
import 'x-markdown-vue/style'
import WelcomePanel from './WelcomePanel.vue'

const chatStore = useChatStore()
const senderRef = ref<InstanceType<typeof XSender>>()

const headerTitle = computed(() => {
  return chatStore.currentSession?.title || 'AI 智能助手'
})

const headerSubtitle = computed(() => {
  if (chatStore.loading) return '正在思考中...'
  if (chatStore.currentSession?.title) return '智能对话助手'
  return '有什么可以帮你的？'
})

function handleSubmit() {
  const sender = senderRef.value
  if (!sender) return
  const value = sender.getModelValue()
  const text = value?.text?.trim()
  if (!text) return
  chatStore.sendMessage(text)
  sender.clear()
}

function handleCancel() {
  chatStore.stopGeneration()
}

function handleWelcomeSend(content: string) {
  chatStore.sendMessage(content)
}

async function handleCopy(content: string) {
  await navigator.clipboard.writeText(content)
  ElMessage.success('已复制')
}
</script>

<template>
  <div class="chat-main">
    <!-- 顶部标题栏 -->
    <div class="chat-header">
      <div class="header-avatar">
        <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
          <rect width="32" height="32" rx="8" fill="#F3E8FF"/>
          <circle cx="11" cy="15" r="2.5" fill="#7C3AED"/>
          <circle cx="21" cy="15" r="2.5" fill="#7C3AED"/>
          <path d="M11 22c0 0 2.5 3 5 3s5-3 5-3" stroke="#7C3AED" stroke-width="1.5" fill="none" stroke-linecap="round"/>
        </svg>
      </div>
      <div class="header-info">
        <h2 class="header-title">{{ headerTitle }}</h2>
        <span class="header-subtitle">{{ headerSubtitle }}</span>
      </div>
      <div v-if="chatStore.loading" class="header-status">
        <span class="status-dot" />
      </div>
    </div>

    <!-- 消息区域 -->
    <div class="messages-area">
      <!-- 空状态：欢迎面板 -->
      <WelcomePanel
        v-if="chatStore.messages.length === 0"
        @send="handleWelcomeSend"
      />

      <!-- 消息列表 -->
      <BubbleList
        v-else
        :list="chatStore.messages"
        :auto-scroll="true"
        :virtual="false"
        class="message-list"
      >
        <template #avatar="{ item }">
          <!-- AI 头像 -->
          <div v-if="item.role === 'assistant'" class="avatar avatar-ai">
            <svg viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect width="36" height="36" rx="10" fill="#F3E8FF"/>
              <circle cx="12" cy="16" r="2.5" fill="#7C3AED"/>
              <circle cx="24" cy="16" r="2.5" fill="#7C3AED"/>
              <path d="M12 24c0 0 2.5 3 6 3s6-3 6-3" stroke="#7C3AED" stroke-width="1.5" fill="none" stroke-linecap="round"/>
            </svg>
          </div>
          <!-- 用户头像 -->
          <div v-else class="avatar avatar-user">
            <svg viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect width="36" height="36" rx="10" fill="#E0E7FF"/>
              <circle cx="18" cy="14" r="6" fill="#6366F1"/>
              <path d="M6 34c0-7 5-11 12-11s12 4 12 11" fill="#6366F1"/>
            </svg>
          </div>
        </template>

        <template #content="{ item }">
          <!-- assistant 消息：Markdown 渲染 + 打字光标 + 底部操作栏 -->
          <div v-if="item.role === 'assistant'" class="message-wrapper">
            <div v-if="!item.content && chatStore.loading" class="loading-dots">
              <span /><span /><span />
            </div>
            <template v-else>
              <div class="markdown-body">
                <MarkdownRenderer :markdown="item.content" />
                <span v-if="chatStore.loading && chatStore.messages[chatStore.messages.length - 1] === item" class="typing-cursor" />
              </div>
              <div class="message-footer">
                <span class="ai-disclaimer">AI 生成内容，仅供参考</span>
                <button class="copy-btn" title="复制内容" @click="handleCopy(item.content)">
                  <svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <rect x="5" y="5" width="9" height="9" rx="2" stroke="currentColor" stroke-width="1.2"/>
                    <path d="M11 5V3.5A1.5 1.5 0 009.5 2h-6A1.5 1.5 0 002 3.5v6A1.5 1.5 0 003.5 11H5" stroke="currentColor" stroke-width="1.2"/>
                  </svg>
                </button>
              </div>
            </template>
          </div>
          <!-- user 消息：纯文本 -->
          <template v-else>{{ item.content }}</template>
        </template>
      </BubbleList>
    </div>

    <!-- 输入区域 -->
    <div class="sender-area">
      <XSender
        ref="senderRef"
        placeholder="输入消息，按 Enter 发送..."
        :loading="chatStore.loading"
        submit-type="enter"
        clearable
        @submit="handleSubmit"
        @cancel="handleCancel"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-main {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--color-bg);
}

/* 顶部标题栏 */
.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 24px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.header-avatar {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}

.header-avatar svg {
  width: 100%;
  height: 100%;
}

.header-info {
  flex: 1;
  min-width: 0;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-subtitle {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.header-status {
  flex-shrink: 0;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-cta);
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.8); }
}

.messages-area {
  flex: 1;
  overflow: hidden;
}

.message-list {
  height: 100%;
}

.sender-area {
  padding: 16px 24px;
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
}

/* 头像 */
.avatar {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}

.avatar svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* 消息包装器 */
.message-wrapper {
  display: flex;
  flex-direction: column;
}

/* 消息底部操作栏 */
.message-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-top: 6px;
  opacity: 0;
  transition: opacity var(--transition);
}

.message-wrapper:hover .message-footer {
  opacity: 1;
}

.ai-disclaimer {
  font-size: 11px;
  color: var(--color-text-secondary);
  opacity: 0.6;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  border-radius: 6px;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition);
  padding: 0;
}

.copy-btn:hover {
  background: var(--color-border-light);
  color: var(--color-primary);
}

.copy-btn svg {
  width: 14px;
  height: 14px;
}

/* Markdown 样式 */
.markdown-body {
  line-height: 1.7;
  font-size: 14px;
  color: var(--color-text);
}

.markdown-body :deep(pre) {
  background: #F9FAFB;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 16px;
  overflow-x: auto;
}

.markdown-body :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'Monaco', 'Menlo', monospace;
  font-size: 13px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 24px;
  margin: 8px 0;
}

.markdown-body :deep(li) {
  margin: 4px 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  color: var(--color-text);
  font-weight: 600;
  margin: 16px 0 8px;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid var(--color-primary-light);
  padding-left: 12px;
  color: var(--color-text-secondary);
  margin: 12px 0;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--color-border);
  padding: 10px 14px;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #F9FAFB;
  font-weight: 600;
  color: var(--color-text);
}

.markdown-body :deep(tr:nth-child(even)) {
  background: #FAFAFA;
}

/* 打字光标 */
.typing-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--color-primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-end infinite;
}

@keyframes blink {
  50% { opacity: 0; }
}

/* 加载动画 */
.loading-dots {
  display: flex;
  gap: 6px;
  padding: 8px 0;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary-light);
  animation: dot-bounce 1.4s ease-in-out infinite;
}

.loading-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.loading-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}
</style>
