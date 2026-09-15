import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useMeetingStore } from './meeting'
import { ApiError } from '@/api/client'
import type { MeetingDetailVO, MeetingVO, PageResult } from '@/types/api'

vi.mock('@/api/meeting', () => ({
  listMeetings: vi.fn(),
  getMeeting: vi.fn(),
  createMeeting: vi.fn(),
  updateMeeting: vi.fn(),
  cancelMeeting: vi.fn(),
  deleteMeeting: vi.fn(),
  endMeetingEarly: vi.fn()
}))
vi.mock('@/api/participant', () => ({
  joinMeeting: vi.fn(),
  leaveMeeting: vi.fn(),
  kickParticipant: vi.fn(),
  listParticipants: vi.fn()
}))

import * as meetingApi from '@/api/meeting'
import * as participantApi from '@/api/participant'

const MEETING: MeetingVO = {
  id: 101,
  title: '产品评审会',
  description: null,
  roomId: 1,
  roomName: '301会议室',
  organizerId: 1001,
  organizerUsername: 'alice',
  startTime: '2026-09-16T10:00:00+08:00',
  endTime: '2026-09-16T11:30:00+08:00',
  status: 'ACTIVE',
  endedEarly: false,
  participantCount: 2,
  createdAt: '2026-09-15T16:30:00+08:00',
  updatedAt: '2026-09-15T16:30:00+08:00'
}

const DETAIL: MeetingDetailVO = { ...MEETING, participants: [] }

const PAGE: PageResult<MeetingVO> = { records: [MEETING], total: 1, page: 1, size: 10 }

describe('meeting store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchMeetings fills list state on success', async () => {
    vi.mocked(meetingApi.listMeetings).mockResolvedValue(PAGE)
    const store = useMeetingStore()
    await store.fetchMeetings({ roomId: 1 })
    expect(store.meetings).toHaveLength(1)
    expect(store.total).toBe(1)
    expect(store.error).toBeNull()
    expect(meetingApi.listMeetings).toHaveBeenCalledWith(
      expect.objectContaining({ roomId: 1, page: 1, size: 10 })
    )
  })

  it('fetchMeetings sets friendly error on failure', async () => {
    vi.mocked(meetingApi.listMeetings).mockRejectedValue(new ApiError(50000, '服务器内部错误', 500))
    const store = useMeetingStore()
    await store.fetchMeetings()
    expect(store.meetings).toEqual([])
    expect(store.error).toBe('服务器内部错误，请稍后重试')
  })

  it('fetchDetail marks notFound on 40401', async () => {
    vi.mocked(meetingApi.getMeeting).mockRejectedValue(new ApiError(40401, '资源不存在', 404))
    const store = useMeetingStore()
    await store.fetchDetail(999)
    expect(store.detail).toBeNull()
    expect(store.detailNotFound).toBe(true)
    expect(store.detailError).toBe('会议不存在或已删除')
  })

  it('fetchDetail stores detail on success', async () => {
    vi.mocked(meetingApi.getMeeting).mockResolvedValue(DETAIL)
    const store = useMeetingStore()
    await store.fetchDetail(101)
    expect(store.detail?.id).toBe(101)
    expect(store.detailNotFound).toBe(false)
  })

  it('join calls api then refreshes detail', async () => {
    vi.mocked(participantApi.joinMeeting).mockResolvedValue({
      id: 503,
      meetingId: 101,
      accountId: 1003,
      username: 'carol',
      isOrganizer: false,
      banned: false,
      joinedAt: '2026-09-15T20:00:00+08:00',
      leftAt: null,
      leaveReason: null
    })
    vi.mocked(meetingApi.getMeeting).mockResolvedValue(DETAIL)
    const store = useMeetingStore()
    await store.join(101)
    expect(participantApi.joinMeeting).toHaveBeenCalledWith(101)
    expect(meetingApi.getMeeting).toHaveBeenCalledWith(101)
  })

  it('leave and kick propagate ApiError to caller', async () => {
    vi.mocked(participantApi.leaveMeeting).mockRejectedValue(new ApiError(40909, '不可退出', 409))
    vi.mocked(participantApi.kickParticipant).mockRejectedValue(new ApiError(40301, '无权限', 403))
    const store = useMeetingStore()
    await expect(store.leave(101)).rejects.toMatchObject({ code: 40909 })
    await expect(store.kick(101, 1002)).rejects.toMatchObject({ code: 40301 })
  })
})
