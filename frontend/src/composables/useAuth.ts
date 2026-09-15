// 鉴权组合式函数：用户信息 + 退出登录
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { useMeetingStore } from '@/stores/meeting'
import { useRoomStore } from '@/stores/room'

export function useAuth() {
  const authStore = useAuthStore()
  const notificationStore = useNotificationStore()
  const meetingStore = useMeetingStore()
  const roomStore = useRoomStore()
  const router = useRouter()
  const { user, isAuthenticated, isAdmin } = storeToRefs(authStore)

  const username = computed(() => user.value?.username ?? '')

  /**
   * 退出登录：无论服务端登出成败，本地态清理与跳转登录页始终执行；
   * 登出接口的异常在清理完成后原样抛出，由调用方提示
   */
  async function logout(): Promise<void> {
    let logoutError: unknown = null
    try {
      await authStore.logout()
    } catch (e) {
      logoutError = e
    }
    // 无论服务端登出成败，本地态清理与跳转登录页始终执行
    notificationStore.clear()
    meetingStore.clear()
    roomStore.clear()
    try {
      await router.push({ name: 'login' })
    } catch (e) {
      // 路由跳转失败不覆盖登出接口的原始错误
      if (!logoutError) logoutError = e
    }
    if (logoutError) throw logoutError
  }

  return { user, username, isAuthenticated, isAdmin, logout }
}
