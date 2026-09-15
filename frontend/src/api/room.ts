// 会议室 API（/api/rooms/*）
// 管理端点（create/update/status/delete）本期无管理页 UI，按契约完整封装
import { request } from './client'
import type {
  CreateRoomRequest,
  RoomAvailabilityVO,
  RoomStatusRequest,
  RoomVO,
  UpdateRoomRequest
} from '@/types/api'

export function listRooms(enabled?: boolean): Promise<RoomVO[]> {
  return request<RoomVO[]>({
    method: 'GET',
    url: '/api/rooms',
    params: enabled === undefined ? {} : { enabled }
  })
}

export function getRoom(id: number): Promise<RoomVO> {
  return request<RoomVO>({ method: 'GET', url: `/api/rooms/${id}` })
}

/** 返回与日期范围有交集的 ACTIVE 占用时段，空闲区间由前端计算 */
export function getRoomAvailability(
  id: number,
  startDate: string,
  endDate?: string
): Promise<RoomAvailabilityVO> {
  return request<RoomAvailabilityVO>({
    method: 'GET',
    url: `/api/rooms/${id}/availability`,
    params: endDate ? { startDate, endDate } : { startDate }
  })
}

export function createRoom(req: CreateRoomRequest): Promise<RoomVO> {
  return request<RoomVO>({ method: 'POST', url: '/api/rooms', data: req })
}

export function updateRoom(id: number, req: UpdateRoomRequest): Promise<RoomVO> {
  return request<RoomVO>({ method: 'PUT', url: `/api/rooms/${id}`, data: req })
}

export function updateRoomStatus(id: number, enabled: boolean): Promise<RoomVO> {
  const body: RoomStatusRequest = { enabled }
  return request<RoomVO>({ method: 'PATCH', url: `/api/rooms/${id}/status`, data: body })
}

export function deleteRoom(id: number): Promise<null> {
  return request<null>({ method: 'DELETE', url: `/api/rooms/${id}` })
}
