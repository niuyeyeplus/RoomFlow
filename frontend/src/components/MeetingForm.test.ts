// MeetingForm 表单校验与提交测试（API 层全部 mock）
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { nextTick } from 'vue'
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

function mountForm(meeting: MeetingVO | null = null) {
  return mount(MeetingForm, {
    props: { meeting },
    global: { plugins: [pinia, ElementPlus] }
  })
}

/** 以未来未开始的会议构造编辑用例（时间需通过对齐/窗口校验） */
function futureMeeting(): MeetingVO {
  const [start, end] = futureRange()
  return {
    ...CREATED,
    title: '原会议标题',
    description: '原说明',
    startTime: toIso(start),
    endTime: toIso(end)
  }
}

/** 进行中的会议：已开始、未结束 */
function ongoingMeeting(): MeetingVO {
  const start = new Date(Date.now() - 30 * 60 * 1000)
  start.setSeconds(0, 0)
  return {
    ...CREATED,
    title: '进行中的会议',
    startTime: toIso(start),
    endTime: toIso(new Date(start.getTime() + 60 * 60 * 1000))
  }
}

function toIso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const b = new Date(d.getTime() + 8 * 60 * 60 * 1000)
  return (
    `${b.getUTCFullYear()}-${pad(b.getUTCMonth() + 1)}-${pad(b.getUTCDate())}` +
    `T${pad(b.getUTCHours())}:${pad(b.getUTCMinutes())}:${pad(b.getUTCSeconds())}+08:00`
  )
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

  it('loads enabled rooms into local state without overwriting the shared store cache', async () => {
    // 详情页用 roomStore.rooms 全量缓存做容量判定；
    // 表单按 enabled 拉取的结果若回写共享缓存会污染该判定
    const store = useRoomStore()
    const cached = { ...ROOM, id: 9, name: '停用房间', enabled: false }
    store.rooms = [cached]

    mountForm()
    await flushPromises()

    expect(roomApi.listRooms).toHaveBeenCalledWith(true)
    expect(store.rooms).toEqual([cached])
  })

  it('emits cancel', async () => {
    const wrapper = mountForm()
    await flushPromises()
    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    await cancelBtn?.trigger('click')
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })

  it('edit mode prefills title/description/room/time and calls updateMeeting', async () => {
    const meeting = futureMeeting()
    vi.mocked(meetingApi.updateMeeting).mockResolvedValue({ ...meeting, title: '改后标题' })
    const wrapper = mountForm(meeting)
    await flushPromises()

    // 预填：标题输入框与按钮文案
    const titleInput = wrapper.find('input')
    expect((titleInput.element as HTMLInputElement).value).toBe('原会议标题')
    expect(wrapper.text()).toContain('保存修改')

    await titleInput.setValue('改后标题')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(meetingApi.createMeeting).not.toHaveBeenCalled()
    expect(meetingApi.updateMeeting).toHaveBeenCalledOnce()
    const [id, payload] = vi.mocked(meetingApi.updateMeeting).mock.calls[0]
    expect(id).toBe(meeting.id)
    expect(payload.title).toBe('改后标题')
    expect(payload.description).toBe('原说明')
    expect(payload.roomId).toBe(meeting.roomId)
    expect(payload.startTime).toBe(meeting.startTime)
    expect(payload.endTime).toBe(meeting.endTime)
    expect(wrapper.emitted('success')).toBeTruthy()
  })

  it('edit mode on started meeting locks room/time fields and resubmits originals', async () => {
    const meeting = ongoingMeeting()
    vi.mocked(meetingApi.updateMeeting).mockResolvedValue({ ...meeting, title: '改名' })
    const wrapper = mountForm(meeting)
    await flushPromises()

    // 房间与时间字段禁用，且出现“仅可修改标题与说明”提示
    expect(wrapper.findComponent(ElSelect).props('disabled')).toBe(true)
    expect(wrapper.findComponent(ElDatePicker).props('disabled')).toBe(true)
    expect(wrapper.text()).toContain('仅可修改会议标题与说明')

    await wrapper.find('input').setValue('改名')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(meetingApi.updateMeeting).toHaveBeenCalledOnce()
    const payload = vi.mocked(meetingApi.updateMeeting).mock.calls[0][1]
    // PUT 全量：roomId/startTime/endTime 回传原始值（不同将触发后端 40909）
    expect(payload.title).toBe('改名')
    expect(payload.roomId).toBe(meeting.roomId)
    expect(payload.startTime).toBe(meeting.startTime)
    expect(payload.endTime).toBe(meeting.endTime)
  })

  it('edit across start boundary: lock state refreshes and title-only submit resends originals', async () => {
    // 回归 M1：timeLocked 曾依赖非响应式 Date.now()，弹窗跨过开始时刻后
    // 锁定态不刷新，仅改标题也会因“开始时间不能早于当前时间”被拦截
    // setImmediate 保持真实，保证 flushPromises 可用
    vi.useFakeTimers({
      toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval']
    })
    try {
      // 会议在 2~17 分钟后开始（15min 对齐）
      const start = new Date(Math.ceil((Date.now() + 2 * 60 * 1000) / 900000) * 900000)
      const meeting: MeetingVO = {
        ...CREATED,
        title: '即将开始的会议',
        startTime: toIso(start),
        endTime: toIso(new Date(start.getTime() + 60 * 60 * 1000))
      }
      vi.mocked(meetingApi.updateMeeting).mockResolvedValue({ ...meeting, title: '只改标题' })
      const wrapper = mountForm(meeting)
      await flushPromises()

      // 打开弹窗时尚未开始：房间/时间字段可编辑
      expect(wrapper.findComponent(ElSelect).props('disabled')).toBe(false)
      expect(wrapper.findComponent(ElDatePicker).props('disabled')).toBe(false)

      // 推进时钟跨过开始时刻（start 距 now 最远约 +17min，推进 20min 确保越过）
      vi.advanceTimersByTime(20 * 60 * 1000)
      await nextTick()

      // 锁定态自动刷新：字段禁用并出现提示
      expect(wrapper.findComponent(ElSelect).props('disabled')).toBe(true)
      expect(wrapper.findComponent(ElDatePicker).props('disabled')).toBe(true)
      expect(wrapper.text()).toContain('仅可修改会议标题与说明')

      await wrapper.find('input').setValue('只改标题')
      await wrapper.find('form').trigger('submit')
      await flushPromises()

      // 仅改标题可成功提交：已成过去的原时间/房间原样回传（PUT 全量）
      expect(meetingApi.updateMeeting).toHaveBeenCalledOnce()
      const payload = vi.mocked(meetingApi.updateMeeting).mock.calls[0][1]
      expect(payload.title).toBe('只改标题')
      expect(payload.roomId).toBe(meeting.roomId)
      expect(payload.startTime).toBe(meeting.startTime)
      expect(payload.endTime).toBe(meeting.endTime)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('edit mode still validates required title', async () => {
    const meeting = futureMeeting()
    const wrapper = mountForm(meeting)
    await flushPromises()
    await wrapper.find('input').setValue('')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(meetingApi.updateMeeting).not.toHaveBeenCalled()
  })
})
