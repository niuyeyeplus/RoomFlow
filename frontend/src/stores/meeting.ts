// 会议状态：列表（分页/筛选）、详情（含参会记录）、报名与生命周期操作
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
  MeetingVO,
  UpdateMeetingRequest
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

  /** 将服务端返回的会议同步到详情与列表缓存（错误一律向上抛给视图层） */
  function applyUpdated(meeting: MeetingVO): void {
    // MeetingVO 不含 participants 键，展开合并保留详情中的参会记录
    if (detail.value && detail.value.id === meeting.id) {
      detail.value = { ...detail.value, ...meeting }
    }
    const idx = meetings.value.findIndex((m) => m.id === meeting.id)
    if (idx >= 0) meetings.value[idx] = meeting
  }

  /** 修改会议（PUT 全量）：已开始的会议由表单层锁定房间/时间字段 */
  async function updateMeeting(id: number, req: UpdateMeetingRequest): Promise<MeetingVO> {
    const updated = await meetingApi.updateMeeting(id, req)
    applyUpdated(updated)
    return updated
  }

  /** 取消会议：仅未开始的 ACTIVE 会议可取消（后端校验，40909） */
  async function cancelMeeting(id: number): Promise<MeetingVO> {
    const updated = await meetingApi.cancelMeeting(id)
    applyUpdated(updated)
    return updated
  }

  /** 提前结束：仅进行中的会议可调用（后端校验，40909） */
  async function endMeetingEarly(id: number): Promise<MeetingVO> {
    const updated = await meetingApi.endMeetingEarly(id)
    applyUpdated(updated)
    return updated
  }

  /** 删除会议（逻辑删除）：成功后从本地缓存移除；详情页由视图层跳转离开 */
  async function removeMeeting(id: number): Promise<void> {
    await meetingApi.deleteMeeting(id)
    const before = meetings.value.length
    meetings.value = meetings.value.filter((m) => m.id !== id)
    // 仅当被删行确实在当前分页列表中时扣减 total，避免详情页删除时计数失真
    if (meetings.value.length < before) {
      total.value = Math.max(0, total.value - 1)
    }
    if (detail.value?.id === id) detail.value = null
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
    updateMeeting,
    cancelMeeting,
    endMeetingEarly,
    removeMeeting,
    join,
    leave,
    kick,
    clear
  }
})
