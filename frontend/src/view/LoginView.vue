<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getUserId, isValidUserId } from '@/utils/userId'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

const showRecoverInput = ref(false)
const recoverId = ref('')
const loading = ref(false)

onMounted(() => {
  // 已有 userId，自动跳转
  if (getUserId()) {
    userStore.initialize()
    router.replace('/')
  }
})

function startNewChat() {
  loading.value = true
  userStore.createNewUser()
  router.push('/')
}

function toggleRecover() {
  showRecoverInput.value = !showRecoverInput.value
  recoverId.value = ''
}

function recoverUser() {
  const id = recoverId.value.trim()
  if (!id) {
    ElMessage.warning('请输入用户ID')
    return
  }
  if (!isValidUserId(id)) {
    ElMessage.error('ID格式不正确，应为UUID格式')
    return
  }
  userStore.setUserId(id)
  router.push('/')
}
</script>

<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <h1>AI Chatbot</h1>
        <p class="subtitle">智能对话助手</p>
      </div>

      <div class="login-body">
        <p class="welcome-text">欢迎使用 AI 智能助手，开始您的第一次对话</p>

        <el-button
          type="primary"
          size="large"
          :loading="loading"
          class="start-btn"
          @click="startNewChat"
        >
          开始新对话
        </el-button>

        <div class="recover-section">
          <el-button link type="info" @click="toggleRecover">
            已有用户ID？点击恢复
          </el-button>

          <transition name="el-fade-in">
            <div v-if="showRecoverInput" class="recover-form">
              <el-input
                v-model="recoverId"
                placeholder="输入之前的用户ID"
                clearable
                @keyup.enter="recoverUser"
              />
              <el-button type="success" @click="recoverUser">
                恢复
              </el-button>
            </div>
          </transition>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, var(--color-primary-dark) 0%, var(--color-primary) 50%, #4F46E5 100%);
}

.login-card {
  width: 420px;
  padding: 48px 40px;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg), 0 0 80px rgba(124, 58, 237, 0.15);
}

.login-header {
  text-align: center;
  margin-bottom: 36px;
}

.login-header h1 {
  font-size: 32px;
  font-weight: 700;
  color: var(--color-text);
  margin: 0 0 8px;
  letter-spacing: -0.5px;
}

.subtitle {
  color: var(--color-text-secondary);
  font-size: 15px;
  margin: 0;
  font-weight: 400;
}

.login-body {
  text-align: center;
}

.welcome-text {
  color: var(--color-text-secondary);
  font-size: 14px;
  margin-bottom: 28px;
  line-height: 1.6;
}

.start-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  border-color: var(--color-primary);
  transition: all var(--transition);
}

.start-btn:hover {
  background: var(--color-primary-dark);
  border-color: var(--color-primary-dark);
  transform: translateY(-1px);
  box-shadow: 0 4px 16px rgba(124, 58, 237, 0.3);
}

.recover-section {
  margin-top: 24px;
}

.recover-section .el-button {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.recover-form {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}

.recover-form .el-input {
  flex: 1;
}
</style>
