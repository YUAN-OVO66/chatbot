<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { ElMessageBox } from 'element-plus'
import ChatSidebar from '@/components/ChatSidebar.vue'
import ChatMain from '@/components/ChatMain.vue'
import ManagementPanel from '@/components/ManagementPanel.vue'

const router = useRouter()
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

async function handleSwitchUser() {
  await ElMessageBox.confirm('切换用户将清除当前会话数据，是否继续？', '切换用户', {
    confirmButtonText: '确认切换',
    cancelButtonText: '取消',
    type: 'warning',
  })
  userStore.clearUser()
  chatStore.$reset()
  router.replace('/login')
}
</script>

<template>
  <div class="chat-view">
    <aside class="sidebar">
      <ChatSidebar />
      <div class="sidebar-footer">
        <el-button link @click="showManagement = true">
          管理面板
        </el-button>
        <el-button link @click="handleSwitchUser">
          切换用户
        </el-button>
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
  background: var(--color-bg);
}

.sidebar {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
}

.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.sidebar-footer .el-button {
  color: var(--color-text-secondary);
  font-size: 13px;
  transition: color var(--transition);
}

.sidebar-footer .el-button:hover {
  color: var(--color-primary);
}

.main {
  flex: 1;
  min-width: 0;
}
</style>
