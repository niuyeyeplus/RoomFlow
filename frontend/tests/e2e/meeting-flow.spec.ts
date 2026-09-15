// 会议流程 E2E：创建（含表单校验负例）-> 分享链接 -> 报名 -> 踢人 -> 禁报 -> 站内通知已读
import { expect, test } from '@playwright/test'
import {
  apiFetch,
  apiRegister,
  fillTimeRange,
  findFreeSlot,
  pickRoom,
  uiLogin,
  uniqName
} from './helpers'

const CREATE_BTN = /创\s*建\s*会\s*议/
const JOIN_BTN = /报\s*名\s*参\s*会/
const KICK_BTN = /移\s*出/

test.describe('meeting-flow', () => {
  test('创建会议表单校验负例：过去时间 / 非15分钟步长被拒', async ({ page }) => {
    const username = uniqName('e2e_org')
    const tokens = await apiRegister(username)
    const room = await pickRoom(tokens.accessToken)
    await uiLogin(page, username)
    await page.goto('/meetings')

    await page.getByRole('button', { name: CREATE_BTN }).click()
    const dialog = page.locator('.el-dialog:visible')
    await expect(dialog).toBeVisible()
    await dialog.locator('input[placeholder="请输入会议标题"]').fill('E2E校验负例')
    await dialog.locator('.el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: room.name }).click()

    // 负例1：过去时间（起止均 15 分钟对齐的昨天槽位）-> “开始时间不能早于当前时间”
    const pastStart = new Date(Date.now() - 24 * 3600 * 1000)
    pastStart.setMinutes(0, 0, 0)
    const pastEnd = new Date(pastStart.getTime() + 30 * 60 * 1000)
    await fillTimeRange(page, pastStart, pastEnd)
    await dialog.getByRole('button', { name: CREATE_BTN }).click()
    await expect(
      dialog.locator('.el-form-item__error', { hasText: '开始时间不能早于当前时间' })
    ).toBeVisible()
    // 未发出创建请求：仍在列表页、无成功提示
    await expect(page).toHaveURL(/\/meetings$/)

    // 负例2：非15分钟步长（未来的 :07 开始）-> “起止时间须按15分钟对齐”
    const badStart = new Date(Date.now() + 6 * 3600 * 1000)
    badStart.setMinutes(7, 0, 0)
    const badEnd = new Date(badStart.getTime() + 30 * 60 * 1000)
    await fillTimeRange(page, badStart, badEnd)
    await dialog.getByRole('button', { name: CREATE_BTN }).click()
    await expect(
      dialog.locator('.el-form-item__error', { hasText: '起止时间须按15分钟对齐' })
    ).toBeVisible()
    await expect(page).toHaveURL(/\/meetings$/)

    await dialog.getByRole('button', { name: /取\s*消/ }).click()
    await expect(dialog).not.toBeVisible()
  })

  test('创建会议 -> 分享链接 -> 第二用户报名 -> 发起人踢人 -> 禁报 -> 通知已读', async ({
    page,
    browser
  }) => {
    // Two browser contexts + cold Vite transforms + async MQ notification polling.
    test.setTimeout(300_000)
    const organizer = uniqName('e2e_org')
    const joiner = uniqName('e2e_join')
    const orgTokens = await apiRegister(organizer)
    const joinerTokens = await apiRegister(joiner)
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)
    const title = `E2E验收会议 ${uniqName('m')}`

    // ---- 发起人 UI：登录 + 创建会议 ----
    await uiLogin(page, organizer)
    await page.goto('/meetings')
    await page.getByRole('button', { name: CREATE_BTN }).click()
    const dialog = page.locator('.el-dialog:visible')
    await dialog.locator('input[placeholder="请输入会议标题"]').fill(title)
    await dialog.locator('.el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: room.name }).click()
    await fillTimeRange(page, slot.start, slot.end)
    const createRespPromise = page
      .waitForResponse(
        (r) => r.url().includes('/api/meetings') && r.request().method() === 'POST',
        { timeout: 60_000 }
      )
      .catch(() => null)
    await dialog.getByRole('button', { name: CREATE_BTN }).click()
    const createResp = await createRespPromise
    expect(
      createResp?.status(),
      `POST /api/meetings -> ${createResp ? `${createResp.status()} ${await createResp.text().catch(() => '')}` : 'no response'}`
    ).toBe(201)

    // 创建成功 -> 跳转详情页
    await page.waitForURL(/\/meetings\/\d+/, { timeout: 60_000 })
    const meetingId = Number(page.url().match(/\/meetings\/(\d+)/)![1])
    await expect(page.locator('h2', { hasText: title })).toBeVisible()
    await expect(page.locator('.el-tag', { hasText: '有效' }).first()).toBeVisible()

    // ---- 分享链接 ----
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.getByRole('button', { name: '复制分享链接' }).click()
    await expect(
      page.locator('.el-message--success', { hasText: '分享链接已复制' })
    ).toBeVisible()
    const shareLink = await page.evaluate(() => navigator.clipboard.readText())
    expect(shareLink).toContain(`/meetings/${meetingId}`)

    // ---- 第二用户：新 context UI 登录 -> 打开分享链接 -> 报名 ----
    // 说明：经隧道的后端调用可能 >15s，ElMessage toast 仅存活 ~3s；
    // 因此动作断言以真实 API 响应 + 持久化 UI 状态为准，不依赖瞬态 toast。
    const ctx2 = await browser.newContext()
    const page2 = await ctx2.newPage()
    await uiLogin(page2, joiner)
    await page2.goto(shareLink)
    await expect(page2.locator('h2', { hasText: title })).toBeVisible()
    const joinRespPromise = page2.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${meetingId}/participants`) &&
        r.request().method() === 'POST',
      { timeout: 60_000 }
    )
    await page2.getByRole('button', { name: JOIN_BTN }).click()
    const joinResp = await joinRespPromise
    expect(joinResp.status(), `join -> ${joinResp.status()}`).toBe(200)
    const joinerRow = page2.locator('.participant-table .el-table__row', { hasText: joiner })
    await expect(joinerRow).toBeVisible()
    await expect(page2.locator('.participant-table .el-table__row')).toHaveCount(2)

    // ---- 发起人视角：参会者列表出现第二用户，执行踢人 ----
    await page.reload()
    const orgJoinerRow = page.locator('.participant-table .el-table__row', { hasText: joiner })
    await expect(orgJoinerRow).toBeVisible()
    await orgJoinerRow.getByRole('button', { name: KICK_BTN }).click()
    const confirmDialog = page.locator('.el-dialog:visible')
    await expect(confirmDialog).toContainText('移出后该用户将无法再次报名')
    const kickRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${meetingId}/participants/`) &&
        r.request().method() === 'DELETE',
      { timeout: 60_000 }
    )
    await confirmDialog.getByRole('button', { name: KICK_BTN }).click()
    const kickResp = await kickRespPromise
    expect(kickResp.status(), `kick -> ${kickResp.status()}`).toBe(200)
    await expect(orgJoinerRow).toContainText('已被移出')

    // ---- 被踢用户再报名被拒 ----
    await page2.reload()
    const rejoinRespPromise = page2.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${meetingId}/participants`) &&
        r.request().method() === 'POST',
      { timeout: 60_000 }
    )
    await page2.getByRole('button', { name: JOIN_BTN }).click()
    const rejoinResp = await rejoinRespPromise
    expect(rejoinResp.status(), `rejoin -> ${rejoinResp.status()}`).toBe(409)
    // 页面上仍是“已被移出”记录，未新增有效参会行
    await expect(page2.locator('.participant-table .el-table__row')).toHaveCount(2)
    await expect(
      page2.locator('.participant-table .el-table__row', { hasText: joiner })
    ).toContainText('已被移出')
    // API 级断言：JOIN_BANNED = 40904
    const rejoin = await apiFetch('POST', `/api/meetings/${meetingId}/participants`, {
      token: joinerTokens.accessToken
    })
    expect(rejoin.status).toBe(409)
    expect(rejoin.code).toBe(40904)

    // ---- 被踢用户收到站内通知（RabbitMQ 异步投递，轮询等待）----
    await page2.goto('/notifications')
    const kickedCard = page2.locator('.notification-item', { hasText: '你已被移出会议' })
    await expect(async () => {
      await page2.reload()
      await expect(kickedCard).toBeVisible()
    }).toPass({ timeout: 30_000, intervals: [2_000, 3_000, 5_000] })
    await expect(kickedCard).toContainText(title)
    await expect(kickedCard).toHaveClass(/unread/)

    // ---- 标记已读 ----
    const readRespPromise = page2.waitForResponse(
      (r) => /\/api\/notifications\/\d+\/read/.test(r.url()) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await kickedCard.getByRole('button', { name: /标\s*记\s*已\s*读/ }).click()
    const readResp = await readRespPromise
    expect(readResp.status(), `mark read -> ${readResp.status()}`).toBe(200)
    await expect(kickedCard).not.toHaveClass(/unread/)

    // ---- 发起人也应收到报名通知（MQ 链路，API 级断言）----
    await expect(async () => {
      const notes = await apiFetch<{ records: { type: string; meetingId: number }[] }>(
        'GET',
        '/api/notifications',
        { token: orgTokens.accessToken, params: { page: 1, size: 50 } }
      )
      const hit = (notes.data?.records ?? []).find(
        (n) => n.type === 'PARTICIPANT_JOINED' && n.meetingId === meetingId
      )
      expect(hit, `organizer notifications: ${JSON.stringify(notes.data)}`).toBeTruthy()
    }).toPass({ timeout: 30_000, intervals: [2_000, 3_000, 5_000] })

    await ctx2.close()
    // 清理说明：本切片后端未实现会议取消/删除端点（属 PR-4），
    // ACTIVE 会议数据残留无法通过 API 清除；已通过唯一标题+未来槽位规避冲突。
  })
})
