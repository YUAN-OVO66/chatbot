import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUserId, getOrCreateUserId, setUserId as saveUserId, clearUserId as clearStorage } from '@/utils/userId'

export const useUserStore = defineStore('user', () => {
  const userId = ref<string>('')
  const isInitialized = ref(false)

  function initialize() {
    const existing = getUserId()
    if (existing) {
      userId.value = existing
      isInitialized.value = true
    }
  }

  function createNewUser(): string {
    const id = getOrCreateUserId()
    userId.value = id
    isInitialized.value = true
    return id
  }

  function setUserId(id: string) {
    saveUserId(id)
    userId.value = id
    isInitialized.value = true
  }

  function clearUser() {
    clearStorage()
    userId.value = ''
    isInitialized.value = false
  }

  return { userId, isInitialized, initialize, createNewUser, setUserId, clearUser }
})
