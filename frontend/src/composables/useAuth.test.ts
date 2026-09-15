// useAuth.logout 语义：登出接口失败也必须清本地态并跳登录页
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const pushMock = vi.fn().mockResolvedValue(undefined)

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock })
}))

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  getCurrentAccount: vi.fn()
}))

import * as authApi from '@/api/auth'
import { useAuth } from './useAuth'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { useMeetingStore } from '@/stores/meeting'
import { useRoomStore } from '@/stores/room'
import { clearSession } from '@/utils/token'
import type { AccountVO, AuthTokenVO } from '@/types/api'

const TOKENS: AuthTokenVO = {
  tokenType: 'Bearer',
  accessToken: 'access-token-1',
  accessTokenExpiresIn: 1800,
  refreshToken: 'refresh-token-1',
  refreshTokenExpiresIn: 2592000
}

const USER: AccountVO = {
  id: 1,
  username: 'alice',
  role: 'USER',
  status: 1,
  createdAt: '2026-09-15T10:00:00+08:00'
}

/** 在组件 setup 中调用 useAuth（useRouter 依赖注入上下文） */
function setup(): ReturnType<typeof useAuth> {
  let auth!: ReturnType<typeof useAuth>
  mount(
    defineComponent({
      setup() {
        auth = useAuth()
        return () => null
      }
    })
  )
  return auth
}

describe('useAuth.logout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    clearSession()
    vi.clearAllMocks()
    vi.mocked(authApi.login).mockResolvedValue(TOKENS)
    vi.mocked(authApi.getCurrentAccount).mockResolvedValue(USER)
  })

  it('clears all stores and navigates to login even when logout api fails', async () => {
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network down'))
    const auth = setup()
    const authStore = useAuthStore()
    const notificationStore = useNotificationStore()
    const meetingStore = useMeetingStore()
    const roomStore = useRoomStore()

    await authStore.login({ username: 'alice', password: 'password1' })
    // 预置各 store 脏状态
    notificationStore.unreadCount = 5
    notificationStore.error = 'x'
    meetingStore.error = 'x'
    roomStore.error = 'x'

    await expect(auth.logout()).rejects.toThrow('network down')

    expect(authStore.isAuthenticated).toBe(false)
    expect(notificationStore.unreadCount).toBe(0)
    expect(notificationStore.error).toBeNull()
    expect(meetingStore.error).toBeNull()
    expect(meetingStore.meetings).toEqual([])
    expect(roomStore.error).toBeNull()
    expect(pushMock).toHaveBeenCalledWith({ name: 'login' })
  })

  it('navigates to login on successful logout without throwing', async () => {
    vi.mocked(authApi.logout).mockResolvedValue(null)
    const auth = setup()
    const authStore = useAuthStore()
    await authStore.login({ username: 'alice', password: 'password1' })

    await expect(auth.logout()).resolves.toBeUndefined()

    expect(authStore.isAuthenticated).toBe(false)
    expect(pushMock).toHaveBeenCalledWith({ name: 'login' })
  })
})
