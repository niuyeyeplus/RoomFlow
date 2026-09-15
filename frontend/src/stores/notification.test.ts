import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNotificationStore } from './notification'
import { ApiError } from '@/api/client'
import type { NotificationVO, PageResult } from '@/types/api'

vi.mock('@/api/notification', () => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn()
}))

import * as api from '@/api/notification'

const N1: NotificationVO = {
  id: 9001,
  type: 'PARTICIPANT_JOINED',
  title: '新参会者',
  content: '用户 bob 报名了你的会议。',
  meetingId: 101,
  isRead: false,
  createdAt: '2026-09-15T17:00:00+08:00'
}

const PAGE: PageResult<NotificationVO> = { records: [N1], total: 1, page: 1, size: 10 }

describe('notification store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchNotifications fills list state', async () => {
    vi.mocked(api.listNotifications).mockResolvedValue(PAGE)
    const store = useNotificationStore()
    await store.fetchNotifications({ isRead: false })
    expect(store.records).toHaveLength(1)
    expect(api.listNotifications).toHaveBeenCalledWith(expect.objectContaining({ isRead: false }))
  })

  it('fetchUnreadCount uses isRead=false&size=1 contract', async () => {
    vi.mocked(api.listNotifications).mockResolvedValue({ records: [], total: 5, page: 1, size: 1 })
    const store = useNotificationStore()
    await store.fetchUnreadCount()
    expect(api.listNotifications).toHaveBeenCalledWith({ page: 1, size: 1, isRead: false })
    expect(store.unreadCount).toBe(5)
  })

  it('markRead updates record and unread count', async () => {
    vi.mocked(api.listNotifications)
      .mockResolvedValueOnce(PAGE)
      .mockResolvedValueOnce({ records: [], total: 1, page: 1, size: 1 })
    vi.mocked(api.markNotificationRead).mockResolvedValue({ ...N1, isRead: true })
    const store = useNotificationStore()
    await store.fetchNotifications()
    await store.fetchUnreadCount()
    await store.markRead(9001)
    expect(store.records[0].isRead).toBe(true)
    expect(store.unreadCount).toBe(0)
  })

  it('markAllRead marks all records read', async () => {
    vi.mocked(api.listNotifications).mockResolvedValue(PAGE)
    vi.mocked(api.markAllNotificationsRead).mockResolvedValue(1)
    const store = useNotificationStore()
    await store.fetchNotifications()
    const updated = await store.markAllRead()
    expect(updated).toBe(1)
    expect(store.records[0].isRead).toBe(true)
    expect(store.unreadCount).toBe(0)
  })

  it('fetchNotifications sets error on failure', async () => {
    vi.mocked(api.listNotifications).mockRejectedValue(new ApiError(50000, 'err', 500))
    const store = useNotificationStore()
    await store.fetchNotifications()
    expect(store.error).toBe('服务器内部错误，请稍后重试')
  })
})
