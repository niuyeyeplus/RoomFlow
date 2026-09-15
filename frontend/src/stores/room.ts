// 会议室状态：列表（enabled 筛选）、占用时段查询、管理端写操作（仅 ADMIN 可调成功）
import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as roomApi from '@/api/room'
import { friendlyMessage } from '@/utils/errors'
import type { CreateRoomRequest, RoomAvailabilityVO, RoomVO, UpdateRoomRequest } from '@/types/api'

export const useRoomStore = defineStore('room', () => {
  const rooms = ref<RoomVO[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const availability = ref<RoomAvailabilityVO | null>(null)
  const availabilityLoading = ref(false)
  const availabilityError = ref<string | null>(null)

  async function fetchRooms(enabled?: boolean): Promise<void> {
    loading.value = true
    error.value = null
    try {
      rooms.value = await roomApi.listRooms(enabled)
    } catch (e) {
      rooms.value = []
      error.value = friendlyMessage(e)
    } finally {
      loading.value = false
    }
  }

  async function fetchAvailability(
    roomId: number,
    startDate: string,
    endDate?: string
  ): Promise<void> {
    availabilityLoading.value = true
    availabilityError.value = null
    try {
      availability.value = await roomApi.getRoomAvailability(roomId, startDate, endDate)
    } catch (e) {
      availability.value = null
      availabilityError.value = friendlyMessage(e)
    } finally {
      availabilityLoading.value = false
    }
  }

  function clearAvailability(): void {
    availability.value = null
    availabilityError.value = null
  }

  /** 插入或替换列表中的房间并保持 id 升序（与后端 list 排序一致） */
  function upsert(room: RoomVO): void {
    const idx = rooms.value.findIndex((r) => r.id === room.id)
    if (idx >= 0) {
      rooms.value[idx] = room
    } else {
      rooms.value.push(room)
    }
    rooms.value.sort((a, b) => a.id - b.id)
  }

  // ---- 管理端写操作：错误向上抛给调用方（视图层统一 ElMessage 提示） ----

  async function createRoom(req: CreateRoomRequest): Promise<RoomVO> {
    const created = await roomApi.createRoom(req)
    upsert(created)
    return created
  }

  async function updateRoom(id: number, req: UpdateRoomRequest): Promise<RoomVO> {
    const updated = await roomApi.updateRoom(id, req)
    upsert(updated)
    return updated
  }

  async function setRoomEnabled(id: number, enabled: boolean): Promise<RoomVO> {
    const updated = await roomApi.updateRoomStatus(id, enabled)
    upsert(updated)
    return updated
  }

  /** 删除为软删除语义（enabled=false）：行保留，故本地仅更新 enabled 标志 */
  async function removeRoom(id: number): Promise<void> {
    await roomApi.deleteRoom(id)
    const idx = rooms.value.findIndex((r) => r.id === id)
    if (idx >= 0) {
      rooms.value[idx] = { ...rooms.value[idx], enabled: false }
    }
  }

  /** 登出等场景下清空本 store 的全部状态 */
  function clear(): void {
    rooms.value = []
    loading.value = false
    error.value = null
    availability.value = null
    availabilityLoading.value = false
    availabilityError.value = null
  }

  return {
    rooms,
    loading,
    error,
    availability,
    availabilityLoading,
    availabilityError,
    fetchRooms,
    fetchAvailability,
    clearAvailability,
    createRoom,
    updateRoom,
    setRoomEnabled,
    removeRoom,
    clear
  }
})
