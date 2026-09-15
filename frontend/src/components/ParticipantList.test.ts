import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ParticipantList from './ParticipantList.vue'
import type { ParticipantVO } from '@/types/api'

const organizer: ParticipantVO = {
  id: 501,
  meetingId: 101,
  accountId: 1001,
  username: 'alice',
  isOrganizer: true,
  banned: false,
  joinedAt: '2026-09-15T16:30:00+08:00',
  leftAt: null,
  leaveReason: null
}

const active: ParticipantVO = {
  id: 502,
  meetingId: 101,
  accountId: 1002,
  username: 'bob',
  isOrganizer: false,
  banned: false,
  joinedAt: '2026-09-15T17:00:00+08:00',
  leftAt: null,
  leaveReason: null
}

const left: ParticipantVO = {
  ...active,
  id: 503,
  accountId: 1003,
  username: 'carol',
  leftAt: '2026-09-15T18:00:00+08:00',
  leaveReason: 'USER_LEFT'
}

const kicked: ParticipantVO = {
  ...active,
  id: 504,
  accountId: 1004,
  username: 'dave',
  leftAt: '2026-09-15T19:00:00+08:00',
  leaveReason: 'KICKED',
  banned: true
}

/** el-table 行渲染依赖挂载后的布局计算，jsdom 下需等待一个宏任务周期 */
async function mountList(props: Record<string, unknown> = {}) {
  const wrapper = mount(ParticipantList, {
    props: { participants: [organizer, active, left, kicked], ...props },
    global: { plugins: [ElementPlus] }
  })
  await new Promise((resolve) => setTimeout(resolve, 300))
  return wrapper
}

describe('ParticipantList', () => {
  it('renders status per record (leftAt null = 参会中)', async () => {
    const wrapper = await mountList()
    const text = wrapper.text()
    expect(text).toContain('alice')
    expect(text).toContain('发起人')
    expect(text).toContain('参会中')
    expect(text).toContain('已退出')
    expect(text).toContain('已被移出')
  })

  it('shows kick button only for active non-organizer when canKick', async () => {
    const wrapper = await mountList({ canKick: true, currentUserId: 1001 })
    const kickButtons = wrapper.findAll('button').filter((b) => b.text().includes('移出'))
    // 只有 bob（有效、非发起人、非本人）显示移出按钮
    expect(kickButtons).toHaveLength(1)
  })

  it('emits kick with the participant on click', async () => {
    const wrapper = await mountList({ canKick: true, currentUserId: 1001 })
    const kickButton = wrapper.findAll('button').find((b) => b.text().includes('移出'))
    await kickButton?.trigger('click')
    expect(wrapper.emitted('kick')).toBeTruthy()
    expect(wrapper.emitted('kick')?.[0][0]).toMatchObject({ accountId: 1002 })
  })

  it('hides kick column entirely when canKick=false', async () => {
    const wrapper = await mountList({ canKick: false })
    // 确认行已渲染后再断言，避免空表格造成假阳性
    expect(wrapper.text()).toContain('alice')
    // 注意：状态文案“已被移出”含“移出”字样，只能断言不存在移出按钮与操作列
    const kickButtons = wrapper.findAll('button').filter((b) => b.text() === '移出')
    expect(kickButtons).toHaveLength(0)
    expect(wrapper.findAll('th').some((th) => th.text().includes('操作'))).toBe(false)
  })
})
