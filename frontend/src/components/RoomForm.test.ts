// RoomForm 创建/编辑表单校验与提交测试（API 层全部 mock）
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ElementPlus, { ElCheckboxGroup, ElInputNumber } from 'element-plus'
import RoomForm from './RoomForm.vue'
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

let pinia: Pinia

function mountForm(room: RoomVO | null = null) {
  return mount(RoomForm, { props: { room }, global: { plugins: [pinia, ElementPlus] } })
}

describe('RoomForm', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    vi.clearAllMocks()
    vi.mocked(roomApi.createRoom).mockResolvedValue({ ...ROOM, id: 9, name: '新会议室' })
    vi.mocked(roomApi.updateRoom).mockResolvedValue(ROOM)
  })

  it('blocks submit when name and capacity are empty', async () => {
    const wrapper = mountForm()
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(roomApi.createRoom).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入会议室名称')
    expect(wrapper.emitted('success')).toBeUndefined()
  })

  it('input-number clamps out-of-range capacity into 1-100 before submit', async () => {
    const wrapper = mountForm()
    await wrapper.find('input').setValue('会议室A')
    // UI 层无法产生越界值：el-input-number 将 0 钳制为 min=1
    await wrapper.findComponent(ElInputNumber).vm.$emit('update:modelValue', 0)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(roomApi.createRoom).toHaveBeenCalledOnce()
    expect(vi.mocked(roomApi.createRoom).mock.calls[0][0].capacity).toBe(1)
  })

  it('submits valid create payload and emits success', async () => {
    const wrapper = mountForm()
    await wrapper.find('input').setValue('  新会议室  ')
    await wrapper.findComponent(ElInputNumber).vm.$emit('update:modelValue', 10)
    await wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['PROJECTOR'])
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(roomApi.createRoom).toHaveBeenCalledOnce()
    const payload = vi.mocked(roomApi.createRoom).mock.calls[0][0]
    expect(payload.name).toBe('新会议室') // 首尾空白被裁剪
    expect(payload.capacity).toBe(10)
    expect(payload.equipment).toEqual(['PROJECTOR'])
    expect(payload.location).toBeNull()
    expect(wrapper.emitted('success')).toBeTruthy()
  })

  it('edit mode prefills fields and calls updateRoom', async () => {
    const wrapper = mountForm(ROOM)
    await flushPromises()
    const nameInput = wrapper.find('input').element as HTMLInputElement
    expect(nameInput.value).toBe('301会议室')

    await wrapper.find('input').setValue('改名会议室')
    await wrapper.findComponent(ElInputNumber).vm.$emit('update:modelValue', 12)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(roomApi.updateRoom).toHaveBeenCalledOnce()
    const [id, payload] = vi.mocked(roomApi.updateRoom).mock.calls[0]
    expect(id).toBe(1)
    expect(payload.name).toBe('改名会议室')
    expect(payload.location).toBe('3楼东侧')
    expect(payload.capacity).toBe(12)
    expect(payload.equipment).toEqual(['PROJECTOR'])
    expect(roomApi.createRoom).not.toHaveBeenCalled()
  })

  it('emits cancel', async () => {
    const wrapper = mountForm()
    const cancelBtn = wrapper.findAll('button').find((b) => b.text() === '取消')
    await cancelBtn?.trigger('click')
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })
})
