// MeetingForm 表单校验与提交测试（API 层全部 mock）
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ElementPlus, { ElDatePicker, ElSelect } from 'element-plus'
import MeetingForm from './MeetingForm.vue'
import { useRoomStore } from '@/stores/room'
import type { MeetingVO, RoomVO } from '@/types/api'

vi.mock('@/api/meeting', () => ({
  listMeetings: vi.fn(),
  getMeeting: vi.fn(),
  createMeeting: vi.fn(),
  updateMeeting: vi.fn(),
  cancelMeeting: vi.fn(),
  deleteMeeting: vi.fn(),
  endMeetingEarly: vi.fn()
}))
vi.mock('@/api/room', () => ({
  listRooms: vi.fn(),
  getRoom: vi.fn(),
  getRoomAvailability: vi.fn(),
  createRoom: vi.fn(),
  updateRoom: vi.fn(),
  updateRoomStatus: vi.fn(),
  deleteRoom: vi.fn()
}))

import * as meetingApi from '@/api/meeting'
import * as roomApi from '@/api/room'

const ROOM: RoomVO = {
  id: 1,
  name: '301会议室',
  location: '3楼东侧',
  capacity: 8,
  equipment: ['PROJECTOR'],
  enabled: true,
  createdAt: '2026-09-15T09:00:00+08:00',
  updatedAt: '2026-09-15T09:00:00+08:00'
}

const CREATED: MeetingVO = {
  id: 101,
  title: '评审会',
  description: null,
  roomId: 1,
  roomName: '301会议室',
  organizerId: 1001,
  organizerUsername: 'alice',
  startTime: '2026-09-16T10:00:00+08:00',
  endTime: '2026-09-16T11:00:00+08:00',
  status: 'ACTIVE',
  endedEarly: false,
  participantCount: 1,
  createdAt: '2026-09-15T16:30:00+08:00',
  updatedAt: '2026-09-15T16:30:00+08:00'
}

/** 取当前时间之后约 1 小时、15min 对齐的起止时间（时长 1h） */
function futureRange(): [Date, Date] {
  const start = new Date(Math.ceil((Date.now() + 3600 * 1000) / (15 * 60 * 1000)) * 15 * 60 * 1000)
  return [start, new Date(start.getTime() + 60 * 60 * 1000)]
}

let pinia: Pinia

function mountForm() {
  return mount(MeetingForm, { global: { plugins: [pinia, ElementPlus] } })
}

describe('MeetingForm', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    vi.clearAllMocks()
    vi.mocked(roomApi.listRooms).mockResolvedValue([ROOM])
    vi.mocked(meetingApi.createMeeting).mockResolvedValue(CREATED)
  })

  it('blocks submit when required fields are empty', async () => {
    const wrapper = mountForm()
    await flushPromises()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    // el-form-item 的错误文案经 refDebounced(100ms) 渲染，需等待防抖
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(meetingApi.createMeeting).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入会议标题')
    expect(wrapper.emitted('success')).toBeUndefined()
  })

  it('blocks submit when time range violates rules (past start)', async () => {
    const wrapper = mountForm()
    await flushPromises()
    await wrapper.find('input').setValue('评审会')
    await wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 1)
    const past = new Date(Date.now() - 60 * 60 * 1000)
    await wrapper
      .findComponent(ElDatePicker)
      .vm.$emit('update:modelValue', [past, new Date(past.getTime() + 3600 * 1000)])
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(meetingApi.createMeeting).not.toHaveBeenCalled()
  })

  it('blocks submit when duration shorter than 15 minutes', async () => {
    const wrapper = mountForm()
    await flushPromises()
    await wrapper.find('input').setValue('短会')
    await wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 1)
    const start = new Date(Math.ceil((Date.now() + 3600 * 1000) / 900000) * 900000)
    await wrapper
      .findComponent(ElDatePicker)
      .vm.$emit('update:modelValue', [start, new Date(start.getTime() + 5 * 60 * 1000)])
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(meetingApi.createMeeting).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('15分钟')
  })

  it('submits valid form and emits success', async () => {
    const wrapper = mountForm()
    await flushPromises()
    await wrapper.find('input').setValue('评审会')
    await wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 1)
    const [start, end] = futureRange()
    await wrapper.findComponent(ElDatePicker).vm.$emit('update:modelValue', [start, end])
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(meetingApi.createMeeting).toHaveBeenCalledOnce()
    const payload = vi.mocked(meetingApi.createMeeting).mock.calls[0][0]
    expect(payload.title).toBe('评审会')
    expect(payload.roomId).toBe(1)
    expect(payload.startTime).toMatch(/\+08:00$/)
    expect(payload.endTime).toMatch(/\+08:00$/)
    expect(wrapper.emitted('success')).toBeTruthy()
  })

  it('always refetches enabled rooms on mount even when store cache is non-empty', async () => {
    // 场景：列表页按“仅停用”筛选后，store 缓存了非启用房间；
    // 表单必须重新拉取 enabledOnly，否则下拉静默为空
    const store = useRoomStore()
    store.rooms = [{ ...ROOM, enabled: false }]

    mountForm()
    await flushPromises()

    expect(roomApi.listRooms).toHaveBeenCalledWith(true)
  })

  it('emits cancel', async () => {
    const wrapper = mountForm()
    await flushPromises()
    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    await cancelBtn?.trigger('click')
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })
})
