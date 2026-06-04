<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { BubbleList, XSender } from 'vue-element-plus-x'
import { MarkdownRenderer } from 'x-markdown-vue'
import 'x-markdown-vue/style'
import WelcomePanel from './WelcomePanel.vue'

const chatStore = useChatStore()
const senderRef = ref<InstanceType<typeof XSender>>()

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
</script>

<template>
  <div class="chat-main">
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
        <template #content="{ item }">
          <!-- assistant 消息：Markdown 渲染 + 打字光标 -->
          <div v-if="item.role === 'assistant'" class="markdown-body">
            <div v-if="!item.content && chatStore.loading" class="loading-dots">
              <span /><span /><span />
            </div>
            <template v-else>
              <MarkdownRenderer :markdown="item.content" />
              <span v-if="chatStore.loading && chatStore.messages[chatStore.messages.length - 1] === item" class="typing-cursor" />
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
}

.messages-area {
  flex: 1;
  overflow: hidden;
}

.message-list {
  height: 100%;
}

.sender-area {
  padding: 12px 20px;
  border-top: 1px solid #e4e7ed;
}

/* Markdown 样式 */
.markdown-body {
  line-height: 1.6;
  font-size: 14px;
}

.markdown-body :deep(pre) {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
}

.markdown-body :deep(code) {
  font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 13px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #dcdfe6;
  padding: 8px 12px;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f5f7fa;
  font-weight: 600;
}

.markdown-body :deep(tr:nth-child(even)) {
  background: #fafafa;
}

/* 打字光标 */
.typing-cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: #409eff;
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
  gap: 4px;
  padding: 4px 0;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #909399;
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
