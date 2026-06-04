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
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 420px;
  padding: 48px 40px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-header h1 {
  font-size: 28px;
  color: #303133;
  margin: 0 0 8px;
}

.subtitle {
  color: #909399;
  font-size: 14px;
  margin: 0;
}

.login-body {
  text-align: center;
}

.welcome-text {
  color: #606266;
  font-size: 15px;
  margin-bottom: 24px;
  line-height: 1.6;
}

.start-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  border-radius: 8px;
}

.recover-section {
  margin-top: 20px;
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
