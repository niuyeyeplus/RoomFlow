// 站内通知 API（/api/notifications*）
import { request } from './client'
import type { ListNotificationsParams, NotificationVO, PageResult } from '@/types/api'

/** 分页查询本人通知（未读优先）；isRead=false&size=1 可获取未读总数 */
export function listNotifications(
  params: ListNotificationsParams = {}
): Promise<PageResult<NotificationVO>> {
  return request<PageResult<NotificationVO>>({
    method: 'GET',
    url: '/api/notifications',
    params
  })
}

export function markNotificationRead(id: number): Promise<NotificationVO> {
  return request<NotificationVO>({ method: 'PATCH', url: `/api/notifications/${id}/read` })
}

/** 全部标记已读，返回本次更新条数 */
export function markAllNotificationsRead(): Promise<number> {
  return request<number>({ method: 'PATCH', url: '/api/notifications/read-all' })
}
