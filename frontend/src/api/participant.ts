// 报名 API（/api/meetings/{id}/participants*）
import { request } from './client'
import type { ParticipantVO } from '@/types/api'

/** 报名参会（无请求体；退出后重报复用记录行，同样返回 200） */
export function joinMeeting(meetingId: number): Promise<ParticipantVO> {
  return request<ParticipantVO>({
    method: 'POST',
    url: `/api/meetings/${meetingId}/participants`
  })
}

/** 退出本人报名（发起人不可退出） */
export function leaveMeeting(meetingId: number): Promise<null> {
  return request<null>({
    method: 'DELETE',
    url: `/api/meetings/${meetingId}/participants/me`
  })
}

/** 踢出参会者（仅发起人或 ADMIN；被踢永久禁入） */
export function kickParticipant(meetingId: number, accountId: number): Promise<null> {
  return request<null>({
    method: 'DELETE',
    url: `/api/meetings/${meetingId}/participants/${accountId}`
  })
}

/** 全部参会记录（含已退出/被踢），按 joinedAt 升序 */
export function listParticipants(meetingId: number): Promise<ParticipantVO[]> {
  return request<ParticipantVO[]>({
    method: 'GET',
    url: `/api/meetings/${meetingId}/participants`
  })
}
