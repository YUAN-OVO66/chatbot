import { createRouter, createWebHistory } from 'vue-router'

import loginView from '@/view/LoginView.vue'
import chatView from '@/view/ChatView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: loginView,
    },
    {
      path: '/',
      name: 'Chat',
      component: chatView,
      meta: { requiresAuth: true },
    },
  ],
})

// 路由守卫：未设置 userId 时跳转登录页
router.beforeEach((to) => {
  const userId = localStorage.getItem('chatbot_user_id')
  if (to.meta.requiresAuth && !userId) {
    return { name: 'Login' }
  }
  if (to.name === 'Login' && userId) {
    return { name: 'Chat' }
  }
})

export default router
