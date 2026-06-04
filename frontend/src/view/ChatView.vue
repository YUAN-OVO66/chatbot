<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import ChatSidebar from '@/components/ChatSidebar.vue'
import ChatMain from '@/components/ChatMain.vue'
import ManagementPanel from '@/components/ManagementPanel.vue'

const userStore = useUserStore()
const chatStore = useChatStore()
const showManagement = ref(false)

onMounted(async () => {
  // 确保用户已初始化
  if (!userStore.isInitialized) {
    userStore.initialize()
  }

  // 加载会话列表
  await chatStore.fetchSessions()

  // 如果没有会话，创建一个
  if (chatStore.sessions.length === 0) {
    await chatStore.createSession()
  } else {
    // 默认选中第一个会话
    await chatStore.switchSession(chatStore.sessions[0].id)
  }
})
</script>

<template>
  <div class="chat-view">
    <aside class="sidebar">
      <ChatSidebar />
      <div class="sidebar-footer">
        <el-button link @click="showManagement = true">
          管理面板
        </el-button>
        <span class="user-id" :title="userStore.userId">
          ID: {{ userStore.userId?.slice(0, 8) }}...
        </span>
      </div>
    </aside>
    <main class="main">
      <ChatMain />
    </main>

    <!-- 管理面板抽屉 -->
    <el-drawer
      v-model="showManagement"
      title="管理面板"
      size="700px"
      direction="rtl"
    >
      <ManagementPanel />
    </el-drawer>
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.sidebar-footer {
  padding: 8px 12px;
  border-top: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
}

.user-id {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 120px;
}

.main {
  flex: 1;
  min-width: 0;
}
</style>
