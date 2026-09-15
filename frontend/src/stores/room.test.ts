import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRoomStore } from './room'
import { ApiError } from '@/api/client'
import type { RoomVO } from '@/types/api'

vi.mock('@/api/room', () => ({
  listRooms: vi.fn(),
  getRoom: vi.fn(),
  getRoomAvailability: vi.fn(),
  createRoom: vi.fn(),
  updateRoom: vi.fn(),
  updateRoomStatus: vi.fn(),
  deleteRoom: vi.fn()
}))

import * as roomApi from '@/api/room'

function room(id: number, enabled = true): RoomVO {
  return {
    id,
    name: `room-${id}`,
    location: null,
    capacity: 8,
    equipment: [],
    enabled,
    createdAt: '2026-09-15T09:00:00+08:00',
    updatedAt: '2026-09-15T09:00:00+08:00'
  }
}

describe('room store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchRooms fills list and forwards enabled filter', async () => {
    vi.mocked(roomApi.listRooms).mockResolvedValue([room(1), room(2, false)])
    const store = useRoomStore()
    await store.fetchRooms(false)
    expect(roomApi.listRooms).toHaveBeenCalledWith(false)
    expect(store.rooms).toHaveLength(2)
    expect(store.error).toBeNull()
  })

  it('createRoom appends to list keeping id order', async () => {
    vi.mocked(roomApi.listRooms).mockResolvedValue([room(1), room(5)])
    vi.mocked(roomApi.createRoom).mockResolvedValue(room(3))
    const store = useRoomStore()
    await store.fetchRooms()
    const created = await store.createRoom({ name: '新会议室', capacity: 10 })
    expect(created.id).toBe(3)
    expect(store.rooms.map((r) => r.id)).toEqual([1, 3, 5])
  })

  it('updateRoom replaces the existing row', async () => {
    vi.mocked(roomApi.listRooms).mockResolvedValue([room(1)])
    vi.mocked(roomApi.updateRoom).mockResolvedValue({ ...room(1), name: '改名后', capacity: 20 })
    const store = useRoomStore()
    await store.fetchRooms()
    await store.updateRoom(1, { name: '改名后', capacity: 20 })
    expect(store.rooms[0].name).toBe('改名后')
    expect(store.rooms[0].capacity).toBe(20)
  })

  it('setRoomEnabled toggles enabled flag in list', async () => {
    vi.mocked(roomApi.listRooms).mockResolvedValue([room(1)])
    vi.mocked(roomApi.updateRoomStatus).mockResolvedValue(room(1, false))
    const store = useRoomStore()
    await store.fetchRooms()
    const updated = await store.setRoomEnabled(1, false)
    expect(roomApi.updateRoomStatus).toHaveBeenCalledWith(1, false)
    expect(updated.enabled).toBe(false)
    expect(store.rooms[0].enabled).toBe(false)
  })

  it('setRoomEnabled propagates 40907 conflict to caller', async () => {
    vi.mocked(roomApi.updateRoomStatus).mockRejectedValue(
      new ApiError(40907, '房间存在进行中的会议', 409)
    )
    const store = useRoomStore()
    await expect(store.setRoomEnabled(1, false)).rejects.toMatchObject({ code: 40907 })
  })

  it('removeRoom is soft delete: row kept with enabled=false', async () => {
    vi.mocked(roomApi.listRooms).mockResolvedValue([room(1), room(2)])
    vi.mocked(roomApi.deleteRoom).mockResolvedValue(null)
    const store = useRoomStore()
    await store.fetchRooms()
    await store.removeRoom(2)
    expect(roomApi.deleteRoom).toHaveBeenCalledWith(2)
    expect(store.rooms).toHaveLength(2)
    expect(store.rooms[1].enabled).toBe(false)
  })

  it('removeRoom propagates 40907 conflict to caller', async () => {
    vi.mocked(roomApi.deleteRoom).mockRejectedValue(new ApiError(40907, '存在未结束会议', 409))
    const store = useRoomStore()
    await expect(store.removeRoom(1)).rejects.toMatchObject({ code: 40907 })
  })
})
