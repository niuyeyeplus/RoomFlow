// 会话凭证本地存储（accessToken / refreshToken / 用户信息缓存）
// 独立成模块以避免 api/client.ts 与 stores/auth.ts 之间形成循环依赖
import type { AccountVO, AuthTokenVO } from '@/types/api'

const ACCESS_KEY = 'roomflow.accessToken'
const REFRESH_KEY = 'roomflow.refreshToken'
const USER_KEY = 'roomflow.user'

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}

export function setTokens(tokens: AuthTokenVO): void {
  localStorage.setItem(ACCESS_KEY, tokens.accessToken)
  localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
}

export function getCachedUser(): AccountVO | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AccountVO
  } catch {
    return null
  }
}

export function setCachedUser(user: AccountVO): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearSession(): void {
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USER_KEY)
}
