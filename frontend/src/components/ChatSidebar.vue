<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { Conversations } from 'vue-element-plus-x'
import type { ConversationMenuCommand } from 'vue-element-plus-x/types/Conversations'
import type { Session } from '@/types'

const chatStore = useChatStore()

const active = computed({
  get: () => chatStore.currentSessionId || '',
  set: (val) => {
    if (val) chatStore.switchSession(val as string)
  },
})

const items = computed(() =>
  chatStore.sessions.map((s: Session) => ({
    id: s.id,
    title: s.title || '新对话',
  }))
)

async function handleNewSession() {
  await chatStore.createSession()
}

function handleMenuCommand(command: ConversationMenuCommand, item: any) {
  if (command === 'delete') {
    chatStore.deleteSession(item.id)
  }
}

function handleLoadMore() {
  const nextPage = chatStore.sessionsPage + 1
  chatStore.fetchSessions(nextPage)
}
</script>

<template>
  <div class="chat-sidebar">
    <div class="sidebar-header">
      <el-button type="primary" class="new-chat-btn" @click="handleNewSession">
        + 新对话
      </el-button>
    </div>

    <Conversations
      v-model:active="active"
      :items="items"
      row-key="id"
      label-key="title"
      show-built-in-menu
      show-built-in-menu-type="hover"
      :load-more-loading="chatStore.sessionsLoading"
      @menu-command="handleMenuCommand"
      @load-more="handleLoadMore"
    />
  </div>
</template>

<style scoped>
.chat-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--color-surface);
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid var(--color-border);
}

.new-chat-btn {
  width: 100%;
  height: 40px;
  font-weight: 600;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  border-color: var(--color-primary);
  transition: all var(--transition);
}

.new-chat-btn:hover {
  background: var(--color-primary-dark);
  border-color: var(--color-primary-dark);
}
</style>
