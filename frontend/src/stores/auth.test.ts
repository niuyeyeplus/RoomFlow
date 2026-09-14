import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('starts unauthenticated', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
  })

  it('sets auth token and user', () => {
    const store = useAuthStore()
    store.setAuth('test-token', { id: 1, username: 'alice', role: 'USER' })
    expect(store.isAuthenticated).toBe(true)
    expect(store.user?.username).toBe('alice')
  })

  it('clears auth on logout', () => {
    const store = useAuthStore()
    store.setAuth('test-token', { id: 1, username: 'alice', role: 'USER' })
    store.clearAuth()
    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
  })
})
