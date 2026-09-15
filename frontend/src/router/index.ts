import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      redirect: '/meetings'
    },
    {
      path: '/rooms',
      name: 'rooms',
      component: () => import('@/views/RoomListView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/meetings',
      name: 'meetings',
      component: () => import('@/views/MeetingListView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/meetings/:id(\\d+)',
      name: 'meeting-detail',
      component: () => import('@/views/MeetingDetailView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/my-meetings',
      name: 'my-meetings',
      component: () => import('@/views/MyMeetingsView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/notifications',
      name: 'notifications',
      component: () => import('@/views/NotificationView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

// 导航守卫：未登录跳登录页（带 redirect 回跳）；meta.requiresAdmin 校验 ADMIN 角色
router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  // 与 localStorage 同步（client 拦截器可能在 store 外清理了会话）
  authStore.syncSession()

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && authStore.isAuthenticated) {
    return { path: '/' }
  }
  if (to.meta.requiresAdmin) {
    const me = await authStore.ensureUser()
    if (!me) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (!authStore.isAdmin) {
      return { name: 'meetings' }
    }
  }
  return true
})

export default router
