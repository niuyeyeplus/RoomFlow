// 认证状态（Pinia setup store）
// token 持久化在 localStorage（utils/token.ts），刷新由 api/client 拦截器完成
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { AccountVO, AuthTokenVO, LoginRequest, RegisterRequest } from '@/types/api'
import {
  clearSession,
  getAccessToken,
  getCachedUser,
  getRefreshToken,
  setCachedUser,
  setTokens
} from '@/utils/token'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AccountVO | null>(getCachedUser())
  const accessToken = ref<string | null>(getAccessToken())
  const refreshToken = ref<string | null>(getRefreshToken())

  const isAuthenticated = computed(() => accessToken.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  /** 从 localStorage 重新同步（client 拦截器可能在 store 外清理会话） */
  function syncSession(): void {
    accessToken.value = getAccessToken()
    refreshToken.value = getRefreshToken()
    user.value = getCachedUser()
  }

  function applyTokens(tokens: AuthTokenVO): void {
    setTokens(tokens)
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
  }

  async function fetchMe(): Promise<AccountVO> {
    const me = await authApi.getCurrentAccount()
    user.value = me
    setCachedUser(me)
    return me
  }

  /** 有 token 但无用户信息时拉取（如刷新页面后） */
  async function ensureUser(): Promise<AccountVO | null> {
    if (!accessToken.value) return null
    if (!user.value) {
      try {
        await fetchMe()
      } catch {
        return null
      }
    }
    return user.value
  }

  async function login(req: LoginRequest): Promise<void> {
    const tokens = await authApi.login(req)
    applyTokens(tokens)
    await fetchMe()
  }

  async function register(req: RegisterRequest): Promise<void> {
    const tokens = await authApi.register(req)
    applyTokens(tokens)
    await fetchMe()
  }

  /** 主动退出：撤销服务端会话（尽力而为），本地始终清理 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clearSession()
      accessToken.value = null
      refreshToken.value = null
      user.value = null
    }
  }

  return {
    user,
    accessToken,
    refreshToken,
    isAuthenticated,
    isAdmin,
    syncSession,
    fetchMe,
    ensureUser,
    login,
    register,
    logout
  }
})
