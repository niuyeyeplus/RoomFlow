// 会议 API（/api/meetings/*）
// 修改/取消/删除/提前结束属 PR-4，本切片不展示对应 UI，仅保留契约封装
import { request } from './client'
import type {
  CreateMeetingRequest,
  ListMeetingsParams,
  MeetingDetailVO,
  MeetingVO,
  PageResult,
  UpdateMeetingRequest
} from '@/types/api'

export function listMeetings(params: ListMeetingsParams = {}): Promise<PageResult<MeetingVO>> {
  return request<PageResult<MeetingVO>>({ method: 'GET', url: '/api/meetings', params })
}

export function getMeeting(id: number): Promise<MeetingDetailVO> {
  return request<MeetingDetailVO>({ method: 'GET', url: `/api/meetings/${id}` })
}

export function createMeeting(req: CreateMeetingRequest): Promise<MeetingVO> {
  return request<MeetingVO>({ method: 'POST', url: '/api/meetings', data: req })
}

export function updateMeeting(id: number, req: UpdateMeetingRequest): Promise<MeetingVO> {
  return request<MeetingVO>({ method: 'PUT', url: `/api/meetings/${id}`, data: req })
}

export function cancelMeeting(id: number): Promise<MeetingVO> {
  return request<MeetingVO>({ method: 'PATCH', url: `/api/meetings/${id}/cancel` })
}

export function deleteMeeting(id: number): Promise<null> {
  return request<null>({ method: 'DELETE', url: `/api/meetings/${id}` })
}

export function endMeetingEarly(id: number): Promise<MeetingVO> {
  return request<MeetingVO>({ method: 'PATCH', url: `/api/meetings/${id}/end-early` })
}
