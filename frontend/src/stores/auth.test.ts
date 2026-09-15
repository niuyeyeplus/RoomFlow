import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { clearSession, getAccessToken, getRefreshToken } from '@/utils/token'
import type { AccountVO, AuthTokenVO } from '@/types/api'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  getCurrentAccount: vi.fn()
}))

import * as authApi from '@/api/auth'

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

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    clearSession()
    vi.clearAllMocks()
  })

  it('starts unauthenticated', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
  })

  it('login stores tokens and loads current user', async () => {
    vi.mocked(authApi.login).mockResolvedValue(TOKENS)
    vi.mocked(authApi.getCurrentAccount).mockResolvedValue(USER)
    const store = useAuthStore()

    await store.login({ username: 'alice', password: 'password1' })

    expect(authApi.login).toHaveBeenCalledWith({ username: 'alice', password: 'password1' })
    expect(store.isAuthenticated).toBe(true)
    expect(store.accessToken).toBe('access-token-1')
    expect(store.user?.username).toBe('alice')
    expect(getAccessToken()).toBe('access-token-1')
    expect(getRefreshToken()).toBe('refresh-token-1')
  })

  it('register stores tokens and loads current user', async () => {
    vi.mocked(authApi.register).mockResolvedValue(TOKENS)
    vi.mocked(authApi.getCurrentAccount).mockResolvedValue(USER)
    const store = useAuthStore()

    await store.register({ username: 'alice', password: 'password1' })

    expect(store.isAuthenticated).toBe(true)
    expect(store.user?.id).toBe(1)
  })

  it('logout calls api and clears session even when api fails', async () => {
    vi.mocked(authApi.login).mockResolvedValue(TOKENS)
    vi.mocked(authApi.getCurrentAccount).mockResolvedValue(USER)
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network'))
    const store = useAuthStore()
    await store.login({ username: 'alice', password: 'password1' })

    await expect(store.logout()).rejects.toThrow()

    expect(authApi.logout).toHaveBeenCalled()
    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
    expect(getAccessToken()).toBeNull()
  })

  it('syncSession picks up externally cleared session', async () => {
    vi.mocked(authApi.login).mockResolvedValue(TOKENS)
    vi.mocked(authApi.getCurrentAccount).mockResolvedValue(USER)
    const store = useAuthStore()
    await store.login({ username: 'alice', password: 'password1' })

    clearSession()
    store.syncSession()

    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
  })

  it('isAdmin reflects user role', async () => {
    vi.mocked(authApi.login).mockResolvedValue(TOKENS)
    vi.mocked(authApi.getCurrentAccount).mockResolvedValue({ ...USER, role: 'ADMIN' })
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'password1' })
    expect(store.isAdmin).toBe(true)
  })
})
