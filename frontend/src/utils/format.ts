// 展示文案映射（中文简洁企业风）
import type { Equipment, LeaveReason, MeetingStatus, NotificationType } from '@/types/api'

export const MEETING_STATUS_TEXT: Record<MeetingStatus, string> = {
  ACTIVE: '有效',
  ENDED: '已结束',
  CANCELLED: '已取消',
  DELETED: '已删除'
}

export const MEETING_STATUS_TAG: Record<MeetingStatus, 'success' | 'info' | 'warning' | 'danger'> =
  {
    ACTIVE: 'success',
    ENDED: 'info',
    CANCELLED: 'warning',
    DELETED: 'danger'
  }

export const EQUIPMENT_TEXT: Record<Equipment, string> = {
  PROJECTOR: '投影仪',
  WHITEBOARD: '白板',
  VIDEO_CONFERENCE: '视频会议',
  PHONE: '电话'
}

export const LEAVE_REASON_TEXT: Record<Exclude<LeaveReason, null>, string> = {
  USER_LEFT: '已退出',
  KICKED: '已被移出'
}

export const NOTIFICATION_TYPE_TEXT: Record<NotificationType, string> = {
  PARTICIPANT_JOINED: '报名通知',
  PARTICIPANT_LEFT: '退出通知',
  PARTICIPANT_KICKED: '移出通知',
  MEETING_ENDED: '结束通知'
}
