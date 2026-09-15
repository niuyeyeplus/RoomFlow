// 管理后台会议室页面测试：列表渲染、启停确认流、删除确认流（API 层全部 mock）
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ElementPlus from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { nextTick } from 'vue'
import RoomManageView from './RoomManageView.vue'
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
    name: `会议室${id}`,
    location: `${id}楼`,
    capacity: 8,
    equipment: ['PROJECTOR'],
    enabled,
    createdAt: '2026-09-15T09:00:00+08:00',
    updatedAt: '2026-09-15T09:00:00+08:00'
  }
}

let pinia: Pinia

function mountView() {
  return mount(RoomManageView, {
    attachTo: document.body,
    global: { plugins: [pinia, ElementPlus], components: { WarningFilled } }
  })
}

/** 在 teleport 到 body 的 dialog 中查找并点击指定文案按钮 */
async function clickDialogButton(text: string): Promise<void> {
  const buttons = Array.from(document.body.querySelectorAll('.el-dialog button'))
  const btn = buttons.find((b) => b.textContent?.includes(text)) as HTMLElement | undefined
  expect(btn, `dialog button "${text}"`).toBeTruthy()
  btn!.click()
  await nextTick()
}

describe('RoomManageView', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    vi.clearAllMocks()
    vi.mocked(roomApi.listRooms).mockResolvedValue([room(1), room(2, false)])
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders all rooms including disabled with status tags', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(roomApi.listRooms).toHaveBeenCalledWith(undefined)
    expect(wrapper.text()).toContain('会议室1')
    expect(wrapper.text()).toContain('会议室2')
    expect(wrapper.text()).toContain('停用')
    wrapper.unmount()
  })

  it('disable flow asks confirmation then calls updateRoomStatus(false)', async () => {
    vi.mocked(roomApi.updateRoomStatus).mockResolvedValue(room(1, false))
    const wrapper = mountView()
    await flushPromises()

    const disableBtn = wrapper.findAll('button').find((b) => b.text() === '停用')
    await disableBtn?.trigger('click')
    await nextTick()
    await nextTick()

    expect(document.body.textContent).toContain('不可再被预约')
    await clickDialogButton('停用')
    await flushPromises()

    expect(roomApi.updateRoomStatus).toHaveBeenCalledWith(1, false)
    wrapper.unmount()
  })

  it('disable conflict (40907) keeps dialog usable and surfaces message', async () => {
    vi.mocked(roomApi.updateRoomStatus).mockRejectedValue(
      new ApiError(40907, '房间存在进行中的会议', 409)
    )
    const wrapper = mountView()
    await flushPromises()
    const disableBtn = wrapper.findAll('button').find((b) => b.text() === '停用')
    await disableBtn?.trigger('click')
    await nextTick()
    await nextTick()
    await clickDialogButton('停用')
    await flushPromises()
    expect(roomApi.updateRoomStatus).toHaveBeenCalledWith(1, false)
    // ElMessage 提示已触发；对话框未被误关
    wrapper.unmount()
  })

  it('enable on disabled room calls updateRoomStatus(true) without confirm dialog', async () => {
    vi.mocked(roomApi.updateRoomStatus).mockResolvedValue(room(2, true))
    const wrapper = mountView()
    await flushPromises()
    const enableBtn = wrapper.findAll('button').find((b) => b.text() === '启用')
    await enableBtn?.trigger('click')
    await flushPromises()
    expect(roomApi.updateRoomStatus).toHaveBeenCalledWith(2, true)
    expect(document.body.querySelector('.el-dialog')).toBeNull()
    wrapper.unmount()
  })

  it('delete flow asks confirmation then calls deleteRoom', async () => {
    vi.mocked(roomApi.deleteRoom).mockResolvedValue(null)
    const wrapper = mountView()
    await flushPromises()
    const deleteBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    await deleteBtn?.trigger('click')
    await nextTick()
    await nextTick()
    expect(document.body.textContent).toContain('软删除')
    await clickDialogButton('删除')
    await flushPromises()
    expect(roomApi.deleteRoom).toHaveBeenCalledWith(1)
    wrapper.unmount()
  })

  it('disabled room row hides delete entry (already soft-deleted)', async () => {
    const wrapper = mountView()
    await flushPromises()
    const rows = wrapper.findAll('.el-table__row')
    const disabledRow = rows.find((r) => r.text().includes('会议室2'))
    expect(disabledRow?.text()).not.toContain('删除')
    wrapper.unmount()
  })

  it('create button opens form dialog', async () => {
    const wrapper = mountView()
    await flushPromises()
    const createBtn = wrapper.findAll('button').find((b) => b.text() === '新建会议室')
    await createBtn?.trigger('click')
    await nextTick()
    await nextTick()
    expect(document.body.textContent).toContain('新建会议室')
    expect(document.body.textContent).toContain('容量')
    wrapper.unmount()
  })
})
