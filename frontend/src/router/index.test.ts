// 路由守卫测试：requiresAdmin 拦截非 ADMIN 用户；未认证访问跳登录
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  refresh: vi.fn(),
  getCurrentAccount: vi.fn()
}))
vi.mock('@/api/room', () => ({
  listRooms: vi.fn().mockResolvedValue([]),
  getRoom: vi.fn(),
  getRoomAvailability: vi.fn(),
  createRoom: vi.fn(),
  updateRoom: vi.fn(),
  updateRoomStatus: vi.fn(),
  deleteRoom: vi.fn()
}))

import router from './index'
import '@/views/LoginView.vue'
import '@/views/admin/RoomManageView.vue'
import '@/views/MeetingListView.vue'
import { setCachedUser, setTokens, clearSession } from '@/utils/token'
import type { AccountVO } from '@/types/api'

function seedSession(role: 'USER' | 'ADMIN'): void {
  setTokens({
    tokenType: 'Bearer',
    accessToken: 'test-access',
    accessTokenExpiresIn: 900,
    refreshToken: 'test-refresh',
    refreshTokenExpiresIn: 2592000
  })
  const user: AccountVO = {
    id: role === 'ADMIN' ? 9 : 1,
    username: role === 'ADMIN' ? 'admin' : 'alice',
    role,
    status: 1,
    createdAt: '2026-09-15T09:00:00+08:00'
  }
  setCachedUser(user)
}

describe('router guard', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    clearSession()
    // 复位到登录页，避免上一用例的路由状态泄漏
    await router.push('/login').catch(() => undefined)
  }, 60000)

  it('redirects unauthenticated admin route access to login', async () => {
    await router.push('/admin/rooms')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/rooms')
  }, 30000)

  it('redirects non-admin user away from admin route', async () => {
    seedSession('USER')
    await router.push('/admin/rooms')
    expect(router.currentRoute.value.name).toBe('meetings')
  }, 30000)

  it('allows ADMIN into admin route', async () => {
    seedSession('ADMIN')
    await router.push('/admin/rooms')
    expect(router.currentRoute.value.name).toBe('admin-rooms')
  }, 30000)

  it('keeps regular authed routes accessible for non-admin', async () => {
    seedSession('USER')
    await router.push('/rooms')
    expect(router.currentRoute.value.name).toBe('rooms')
  }, 30000)
})
