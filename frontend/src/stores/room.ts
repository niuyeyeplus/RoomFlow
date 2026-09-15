// 会议室状态：列表（enabled 筛选）与占用时段查询
import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as roomApi from '@/api/room'
import { friendlyMessage } from '@/utils/errors'
import type { RoomAvailabilityVO, RoomVO } from '@/types/api'

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
    clear
  }
})
