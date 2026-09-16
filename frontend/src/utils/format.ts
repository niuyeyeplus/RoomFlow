// 展示文案映射（中文简洁企业风）
import type {
  Equipment,
  LeaveReason,
  MeetingStatus,
  MeetingVO,
  NotificationType
} from '@/types/api'

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

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/**
 * 会议状态徽章：ACTIVE 按当前时间细分未开始/进行中；
 * ENDED 区分正常结束与提前结束；CANCELLED/DELETED 按状态文案展示。
 * DELETED 对普通用户不可见，不会真实出现。
 */
export function meetingStatusTag(
  meeting: Pick<MeetingVO, 'status' | 'endedEarly' | 'startTime'>,
  now: number = Date.now()
): { text: string; type: TagType } {
  if (meeting.status === 'ACTIVE') {
    return new Date(meeting.startTime).getTime() <= now
      ? { text: '进行中', type: 'primary' }
      : { text: '未开始', type: 'success' }
  }
  if (meeting.status === 'ENDED') {
    return { text: meeting.endedEarly ? '已提前结束' : '已结束', type: 'info' }
  }
  return { text: MEETING_STATUS_TEXT[meeting.status], type: MEETING_STATUS_TAG[meeting.status] }
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
