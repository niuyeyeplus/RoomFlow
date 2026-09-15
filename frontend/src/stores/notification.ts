// 站内通知状态：分页列表（未读优先）、未读数、标记已读
import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as notificationApi from '@/api/notification'
import { friendlyMessage } from '@/utils/errors'
import type { ListNotificationsParams, NotificationVO } from '@/types/api'

export const useNotificationStore = defineStore('notification', () => {
  const records = ref<NotificationVO[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(10)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const unreadCount = ref(0)

  async function fetchNotifications(params: ListNotificationsParams = {}): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await notificationApi.listNotifications({
        page: page.value,
        size: size.value,
        ...params
      })
      records.value = result.records
      total.value = result.total
      page.value = result.page
      size.value = result.size
    } catch (e) {
      records.value = []
      total.value = 0
      page.value = 1
      error.value = friendlyMessage(e)
    } finally {
      loading.value = false
    }
  }

  /** 契约口径：isRead=false&size=1，total 即未读数 */
  async function fetchUnreadCount(): Promise<void> {
    try {
      const result = await notificationApi.listNotifications({ page: 1, size: 1, isRead: false })
      unreadCount.value = result.total
    } catch {
      unreadCount.value = 0
    }
  }

  async function markRead(id: number): Promise<void> {
    await notificationApi.markNotificationRead(id)
    const record = records.value.find((r) => r.id === id)
    if (record && !record.isRead) {
      record.isRead = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
  }

  async function markAllRead(): Promise<number> {
    const updated = await notificationApi.markAllNotificationsRead()
    records.value.forEach((r) => {
      r.isRead = true
    })
    unreadCount.value = 0
    return updated
  }

  function clear(): void {
    records.value = []
    total.value = 0
    page.value = 1
    unreadCount.value = 0
    error.value = null
  }

  return {
    records,
    total,
    page,
    size,
    loading,
    error,
    unreadCount,
    fetchNotifications,
    fetchUnreadCount,
    markRead,
    markAllRead,
    clear
  }
})
