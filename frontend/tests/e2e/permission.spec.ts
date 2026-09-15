// 权限边界 E2E：本切片无管理页 UI，采用“路由兜底 + API 级 403/401”替代断言。
import { expect, test } from '@playwright/test'
import {
  apiFetch,
  apiRegister,
  beijingIso,
  findFreeSlot,
  pickRoom,
  uiLogin,
  uniqName
} from './helpers'

interface MeetingVO {
  id: number
}

test.describe('permission-boundary', () => {
  test('普通用户：不存在的管理路由兜底重定向 + ADMIN 接口 403 + 未认证 401', async ({ page }) => {
    const username = uniqName('e2e_perm')
    const tokens = await apiRegister(username)
    await uiLogin(page, username)

    // 不存在的管理/任意路由 -> 通配路由重定向 '/' -> '/meetings'
    // （本切片无管理页；用“无隐藏入口、兜底回落”代替管理页访问断言）
    await page.goto('/admin')
    await expect(page).toHaveURL(/\/meetings$/)
    await page.goto('/admin/rooms/whatever')
    await expect(page).toHaveURL(/\/meetings$/)
    // 导航栏不含任何管理入口
    await expect(page.locator('.nav-menu')).not.toContainText('管理')

    // ADMIN-only：创建/改状态/删除会议室均被拒（40301 FORBIDDEN）
    const create = await apiFetch('POST', '/api/rooms', {
      token: tokens.accessToken,
      data: { name: 'E2E越权房间', capacity: 4 }
    })
    expect(create.status, `POST /api/rooms: ${JSON.stringify(create.raw)}`).toBe(403)
    expect(create.code).toBe(40301)
    const status = await apiFetch('PATCH', '/api/rooms/1/status', {
      token: tokens.accessToken,
      data: { enabled: false }
    })
    expect(status.status).toBe(403)
    const del = await apiFetch('DELETE', '/api/rooms/1', { token: tokens.accessToken })
    expect(del.status).toBe(403)

    // 未认证访问受保护 API -> 401
    const unauth = await apiFetch('GET', '/api/meetings')
    expect(unauth.status).toBe(401)
    const unauthMe = await apiFetch('GET', '/api/auth/me')
    expect(unauthMe.status).toBe(401)
  })

  test('非发起人/非管理员对他人会议执行踢人被拒（403）', async () => {
    const organizer = uniqName('e2e_porg')
    const victim = uniqName('e2e_pvic')
    const outsider = uniqName('e2e_pout')
    const orgTokens = await apiRegister(organizer)
    const victimTokens = await apiRegister(victim)
    const outTokens = await apiRegister(outsider)

    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)
    const created = await apiFetch<MeetingVO>('POST', '/api/meetings', {
      token: orgTokens.accessToken,
      data: {
        title: `E2E越权会议 ${uniqName('m')}`,
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(created.status, `create meeting: ${JSON.stringify(created.raw)}`).toBe(201)
    const meetingId = created.data!.id

    // victim 正常报名
    const join = await apiFetch('POST', `/api/meetings/${meetingId}/participants`, {
      token: victimTokens.accessToken
    })
    expect(join.status).toBe(200)

    // outsider（既非发起人也非 ADMIN）尝试踢人 -> 403
    const victimMe = await apiFetch<{ id: number }>('GET', '/api/auth/me', {
      token: victimTokens.accessToken
    })
    const kick = await apiFetch(
      'DELETE',
      `/api/meetings/${meetingId}/participants/${victimMe.data!.id}`,
      { token: outTokens.accessToken }
    )
    expect(kick.status, `kick by outsider: ${JSON.stringify(kick.raw)}`).toBe(403)
    expect(kick.code).toBe(40301)

    // 同理，非发起人不可见“移出”按钮由 UI canKick 控制；此处用 API 边界断言替代
    // 残留说明：会议创建后本切片无取消/删除端点，数据以唯一标题规避冲突。
  })
})
