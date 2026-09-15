// 会议状态：列表（分页/筛选）、详情（含参会记录）、报名操作
import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as meetingApi from '@/api/meeting'
import * as participantApi from '@/api/participant'
import { ApiError } from '@/api/client'
import { friendlyMessage } from '@/utils/errors'
import type {
  CreateMeetingRequest,
  ListMeetingsParams,
  MeetingDetailVO,
  MeetingVO
} from '@/types/api'

export const useMeetingStore = defineStore('meeting', () => {
  const meetings = ref<MeetingVO[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(10)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const detail = ref<MeetingDetailVO | null>(null)
  const detailLoading = ref(false)
  const detailError = ref<string | null>(null)
  const detailNotFound = ref(false)

  async function fetchMeetings(params: ListMeetingsParams = {}): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await meetingApi.listMeetings({
        page: page.value,
        size: size.value,
        ...params
      })
      meetings.value = result.records
      total.value = result.total
      page.value = result.page
      size.value = result.size
    } catch (e) {
      meetings.value = []
      total.value = 0
      page.value = 1
      error.value = friendlyMessage(e)
    } finally {
      loading.value = false
    }
  }

  async function fetchDetail(id: number): Promise<void> {
    detailLoading.value = true
    detailError.value = null
    detailNotFound.value = false
    try {
      detail.value = await meetingApi.getMeeting(id)
    } catch (e) {
      detail.value = null
      if (e instanceof ApiError && e.code === 40401) {
        detailNotFound.value = true
      }
      detailError.value =
        e instanceof ApiError && e.code === 40401 ? '会议不存在或已删除' : friendlyMessage(e)
    } finally {
      detailLoading.value = false
    }
  }

  /** 创建会议：校验/冲突错误（ApiError）原样抛出，由表单处理 fieldErrors */
  async function createMeeting(req: CreateMeetingRequest): Promise<MeetingVO> {
    return meetingApi.createMeeting(req)
  }

  async function join(id: number): Promise<void> {
    await participantApi.joinMeeting(id)
    await fetchDetail(id)
  }

  async function leave(id: number): Promise<void> {
    await participantApi.leaveMeeting(id)
    await fetchDetail(id)
  }

  async function kick(id: number, accountId: number): Promise<void> {
    await participantApi.kickParticipant(id, accountId)
    await fetchDetail(id)
  }

  /** 登出等场景下清空本 store 的全部状态 */
  function clear(): void {
    meetings.value = []
    total.value = 0
    page.value = 1
    size.value = 10
    loading.value = false
    error.value = null
    detail.value = null
    detailLoading.value = false
    detailError.value = null
    detailNotFound.value = false
  }

  return {
    meetings,
    total,
    page,
    size,
    loading,
    error,
    detail,
    detailLoading,
    detailError,
    detailNotFound,
    fetchMeetings,
    fetchDetail,
    createMeeting,
    join,
    leave,
    kick,
    clear
  }
})
