// 会议详情页测试：权限可见性、生命周期确认流、状态徽章与报名不可报名提示（API 层全部 mock）
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import MeetingDetailView from './MeetingDetailView.vue'
import { useAuthStore } from '@/stores/auth'
import type { AccountVO, MeetingDetailVO, ParticipantVO, RoomVO } from '@/types/api'

const routerMocks = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ name: 'meeting-detail', params: { id: '101' } }),
  useRouter: () => ({ push: routerMocks.push })
}))
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
vi.mock('@/api/room', () => ({
  listRooms: vi.fn(),
  getRoom: vi.fn(),
  getRoomAvailability: vi.fn(),
  createRoom: vi.fn(),
  updateRoom: vi.fn(),
  updateRoomStatus: vi.fn(),
  deleteRoom: vi.fn()
}))
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  getCurrentAccount: vi.fn()
}))

import * as meetingApi from '@/api/meeting'
import * as roomApi from '@/api/room'

const ROOM: RoomVO = {
  id: 1,
  name: '301会议室',
  location: '3楼',
  capacity: 8,
  equipment: [],
  enabled: true,
  createdAt: '2026-09-15T09:00:00+08:00',
  updatedAt: '2026-09-15T09:00:00+08:00'
}

function toIso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const b = new Date(d.getTime() + 8 * 60 * 60 * 1000)
  return (
    `${b.getUTCFullYear()}-${pad(b.getUTCMonth() + 1)}-${pad(b.getUTCDate())}` +
    `T${pad(b.getUTCHours())}:${pad(b.getUTCMinutes())}:${pad(b.getUTCSeconds())}+08:00`
  )
}

/** 默认构造“未开始”的 ACTIVE 会议详情；用 overrides 覆盖状态/时间/参会记录 */
function makeDetail(overrides: Partial<MeetingDetailVO> = {}): MeetingDetailVO {
  const start = new Date(Date.now() + 60 * 60 * 1000)
  start.setSeconds(0, 0)
  return {
    id: 101,
    title: '评审会',
    description: null,
    roomId: 1,
    roomName: '301会议室',
    organizerId: 1001,
    organizerUsername: 'alice',
    startTime: toIso(start),
    endTime: toIso(new Date(start.getTime() + 60 * 60 * 1000)),
    status: 'ACTIVE',
    endedEarly: false,
    participantCount: 1,
    createdAt: '2026-09-15T16:30:00+08:00',
    updatedAt: '2026-09-15T16:30:00+08:00',
    participants: [],
    ...overrides
  }
}

function ongoingDetail(overrides: Partial<MeetingDetailVO> = {}): MeetingDetailVO {
  const start = new Date(Date.now() - 30 * 60 * 1000)
  start.setSeconds(0, 0)
  return makeDetail({
    startTime: toIso(start),
    endTime: toIso(new Date(start.getTime() + 2 * 60 * 60 * 1000)),
    ...overrides
  })
}

function user(id: number, role: 'USER' | 'ADMIN' = 'USER'): AccountVO {
  return { id, username: `user${id}`, role, status: 1, createdAt: '2026-09-15T09:00:00+08:00' }
}

let pinia: Pinia

async function mountWith(detail: MeetingDetailVO, account: AccountVO): Promise<VueWrapper> {
  vi.mocked(meetingApi.getMeeting).mockResolvedValue(detail)
  const auth = useAuthStore()
  auth.accessToken = 'test-token'
  auth.user = account
  const wrapper = mount(MeetingDetailView, {
    attachTo: document.body,
    global: { plugins: [pinia, ElementPlus] }
  })
  await flushPromises()
  return wrapper
}

function findButton(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((b) => b.text() === text)
}

/** 在 teleport 到 body 的 ConfirmDialog 中点击确认按钮 */
async function confirmInDialog(text: string): Promise<void> {
  await nextTick()
  await nextTick()
  const buttons = Array.from(document.body.querySelectorAll('.el-dialog button'))
  const btn = buttons.find((b) => b.textContent?.includes(text)) as HTMLElement | undefined
  expect(btn, `dialog button "${text}"`).toBeTruthy()
  btn!.click()
  await flushPromises()
}

describe('MeetingDetailView', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    vi.clearAllMocks()
    vi.mocked(roomApi.listRooms).mockResolvedValue([ROOM])
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('未开始会议渲染“未开始”徽章，普通用户可报名且无管理按钮', async () => {
    const wrapper = await mountWith(makeDetail(), user(2))
    expect(wrapper.find('.el-tag').text()).toBe('未开始')
    expect(findButton(wrapper, '报名参会')).toBeTruthy()
    expect(findButton(wrapper, '编辑会议')).toBeUndefined()
    expect(findButton(wrapper, '取消会议')).toBeUndefined()
    expect(findButton(wrapper, '删除会议')).toBeUndefined()
    wrapper.unmount()
  })

  it('发起人在未开始会议可见编辑/取消/删除，无提前结束', async () => {
    const wrapper = await mountWith(makeDetail(), user(1001))
    expect(findButton(wrapper, '编辑会议')).toBeTruthy()
    expect(findButton(wrapper, '取消会议')).toBeTruthy()
    expect(findButton(wrapper, '删除会议')).toBeTruthy()
    expect(findButton(wrapper, '提前结束')).toBeUndefined()
    expect(findButton(wrapper, '报名参会')).toBeUndefined()
    wrapper.unmount()
  })

  it('ADMIN 非发起人同样可见管理按钮', async () => {
    const wrapper = await mountWith(makeDetail(), user(9, 'ADMIN'))
    expect(findButton(wrapper, '编辑会议')).toBeTruthy()
    expect(findButton(wrapper, '取消会议')).toBeTruthy()
    expect(findButton(wrapper, '删除会议')).toBeTruthy()
    wrapper.unmount()
  })

  it('进行中会议徽章为“进行中”，发起人可见提前结束而无取消', async () => {
    const wrapper = await mountWith(ongoingDetail(), user(1001))
    expect(wrapper.find('.el-tag').text()).toBe('进行中')
    expect(findButton(wrapper, '提前结束')).toBeTruthy()
    expect(findButton(wrapper, '编辑会议')).toBeTruthy()
    expect(findButton(wrapper, '取消会议')).toBeUndefined()
    wrapper.unmount()
  })

  it('取消会议经 ConfirmDialog 确认后调用 cancelMeeting', async () => {
    const cancelled = makeDetail({ status: 'CANCELLED' })
    vi.mocked(meetingApi.cancelMeeting).mockResolvedValue(cancelled)
    const wrapper = await mountWith(makeDetail(), user(1001))
    await findButton(wrapper, '取消会议')?.trigger('click')
    await confirmInDialog('取消会议')
    expect(meetingApi.cancelMeeting).toHaveBeenCalledWith(101)
    wrapper.unmount()
  })

  it('提前结束经 ConfirmDialog 确认后调用 endMeetingEarly', async () => {
    const ended = ongoingDetail({ status: 'ENDED', endedEarly: true })
    vi.mocked(meetingApi.endMeetingEarly).mockResolvedValue(ended)
    const wrapper = await mountWith(ongoingDetail(), user(1001))
    await findButton(wrapper, '提前结束')?.trigger('click')
    await confirmInDialog('提前结束')
    expect(meetingApi.endMeetingEarly).toHaveBeenCalledWith(101)
    wrapper.unmount()
  })

  it('删除会议经 ConfirmDialog 确认后调用 deleteMeeting 并返回列表页', async () => {
    vi.mocked(meetingApi.deleteMeeting).mockResolvedValue(null)
    const wrapper = await mountWith(makeDetail(), user(1001))
    await findButton(wrapper, '删除会议')?.trigger('click')
    await confirmInDialog('删除')
    expect(meetingApi.deleteMeeting).toHaveBeenCalledWith(101)
    expect(routerMocks.push).toHaveBeenCalledWith({ name: 'meetings' })
    wrapper.unmount()
  })

  it('已取消会议显示“已取消”徽章与不可报名提示，隐藏报名按钮', async () => {
    const wrapper = await mountWith(makeDetail({ status: 'CANCELLED' }), user(2))
    expect(wrapper.find('.el-tag').text()).toBe('已取消')
    expect(wrapper.text()).toContain('会议已取消，无法报名')
    expect(findButton(wrapper, '报名参会')).toBeUndefined()
    wrapper.unmount()
  })

  it('提前结束的会议显示“已提前结束”徽章与提示', async () => {
    const wrapper = await mountWith(makeDetail({ status: 'ENDED', endedEarly: true }), user(2))
    expect(wrapper.find('.el-tag').text()).toBe('已提前结束')
    expect(wrapper.text()).toContain('会议已提前结束，无法报名')
    wrapper.unmount()
  })

  it('已开始会议对未报名用户提示“报名已截止”', async () => {
    const wrapper = await mountWith(ongoingDetail(), user(2))
    expect(wrapper.text()).toContain('会议已开始，报名已截止')
    expect(findButton(wrapper, '报名参会')).toBeUndefined()
    wrapper.unmount()
  })

  it('满员会议提示“报名人数已满”', async () => {
    const wrapper = await mountWith(makeDetail({ participantCount: 8 }), user(2))
    expect(wrapper.text()).toContain('报名人数已满')
    expect(findButton(wrapper, '报名参会')).toBeUndefined()
    wrapper.unmount()
  })

  it('容量未就绪时报名按钮保持加载态，就绪后可点', async () => {
    // 回归 m3：房间列表未返回前 capacity=null，若按钮可点则“已满”判定存在空窗期
    let resolveRooms!: (rooms: RoomVO[]) => void
    vi.mocked(roomApi.listRooms).mockImplementation(
      () =>
        new Promise<RoomVO[]>((resolve) => {
          resolveRooms = resolve
        })
    )
    const wrapper = await mountWith(makeDetail(), user(2))

    const pendingBtn = findButton(wrapper, '报名参会')
    expect(pendingBtn).toBeTruthy()
    expect(pendingBtn!.classes()).toContain('is-loading')

    resolveRooms([ROOM])
    await flushPromises()
    expect(findButton(wrapper, '报名参会')!.classes()).not.toContain('is-loading')
    wrapper.unmount()
  })

  it('被移出用户提示无法再次报名', async () => {
    const kicked: ParticipantVO = {
      id: 501,
      meetingId: 101,
      accountId: 2,
      username: 'user2',
      isOrganizer: false,
      banned: true,
      joinedAt: '2026-09-15T18:00:00+08:00',
      leftAt: '2026-09-15T19:00:00+08:00',
      leaveReason: 'KICKED'
    }
    const wrapper = await mountWith(makeDetail({ participants: [kicked] }), user(2))
    expect(wrapper.text()).toContain('你已被移出该会议，无法再次报名')
    wrapper.unmount()
  })
})
