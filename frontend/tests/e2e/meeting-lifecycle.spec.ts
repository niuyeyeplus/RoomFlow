// 会议生命周期 E2E：修改（未开始全量 / 已开始仅标题说明）-> 取消 -> 删除 -> 提前结束 -> 越权 -> 到期自动结束
//
// 运行前提：
// 1) 中间件经 SSH 隧道可达（127.0.0.1:13306 MySQL / 16379 Redis / 15673 AMQP）；
// 2) 后端以短周期 sweep 启动（到期自动结束场景依赖）：
//      mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--roomflow.scheduler.interval=2s --roomflow.scheduler.lock-at-least=PT1S"
//    注意 lock-at-least 默认 PT50S 会让 ShedLock 压住下一次重扫，必须一并调小；
// 3) “已开始/已到期”会议由 mysql 客户端直接改写 start_time/end_time 造数——
//    契约禁止预约过去时段，UI/API 无法创建；连接参数取 DB_* 环境变量，
//    密码经 MYSQL_PWD 环境变量传入（默认与 application-dev.yml 一致）。
import { execSync } from 'node:child_process'
import { expect, test, type Locator, type Page } from '@playwright/test'
import {
  apiFetch,
  apiRegister,
  beijingIso,
  fillTimeRange,
  findFreeSlot,
  pickRoom,
  uiLogin,
  uniqName,
  type Room
} from './helpers'

const EDIT_BTN = /编\s*辑\s*会\s*议/
const CANCEL_BTN = /取\s*消\s*会\s*议/
const END_EARLY_BTN = /提\s*前\s*结\s*束/
const DELETE_BTN = /删\s*除\s*会\s*议/
const SAVE_BTN = /保\s*存\s*修\s*改/
const JOIN_BTN = /报\s*名\s*参\s*会/
const CONFIRM_DELETE = /删\s*除/

interface MeetingVO {
  id: number
  title: string
  roomId: number
  startTime: string
  endTime: string
  status: 'ACTIVE' | 'ENDED' | 'CANCELLED' | 'DELETED'
  endedEarly: boolean
}

// ---------- 造数：API 建会 + mysql 直改时间 ----------

const MYSQL_BIN = process.env.E2E_MYSQL_BIN ?? 'mysql'
const DB_HOST = process.env.DB_HOST ?? '127.0.0.1'
const DB_PORT = process.env.DB_PORT ?? '13306'
const DB_USER = process.env.DB_USERNAME ?? 'roomflow'
const DB_NAME = process.env.DB_NAME ?? 'roomflow'
const DB_PASSWORD = process.env.DB_PASSWORD ?? ''

/** Date -> 'YYYY-MM-DD HH:mm:ss'（北京时间，meeting 表 LocalDateTime 列的墙钟值） */
function beijingSql(d: Date): string {
  return new Date(d.getTime() + 8 * 3600 * 1000).toISOString().slice(0, 19).replace('T', ' ')
}

/** Date -> 'YYYY-MM-DD HH:mm'（北京时间，与前端 formatDateTime 展示一致） */
function beijingMinute(d: Date): string {
  return beijingSql(d).slice(0, 16)
}

/** 直接改库把会议拨到指定起止时间（造“进行中/已到期”数据）。 */
function dbSetMeetingTimes(meetingId: number, start: Date, end: Date): void {
  if (!DB_PASSWORD) {
    throw new Error('DB_PASSWORD 未设置：本 spec 需 MySQL 写权限造“已开始/已到期”会议')
  }
  const sql =
    `UPDATE meeting SET start_time='${beijingSql(start)}', ` +
    `end_time='${beijingSql(end)}' WHERE id=${Number(meetingId)}`
  execSync(`${MYSQL_BIN} -h ${DB_HOST} -P ${DB_PORT} -u ${DB_USER} ${DB_NAME} -e "${sql}"`, {
    env: { ...process.env, MYSQL_PWD: DB_PASSWORD },
    timeout: 30_000,
    stdio: 'pipe'
  })
}

async function createMeetingApi(
  token: string,
  roomId: number,
  start: Date,
  end: Date,
  title: string
): Promise<MeetingVO> {
  const r = await apiFetch<MeetingVO>('POST', '/api/meetings', {
    token,
    data: { title, roomId, startTime: beijingIso(start), endTime: beijingIso(end) }
  })
  expect(r.status, `create meeting: ${JSON.stringify(r.raw)}`).toBe(201)
  return r.data!
}

/**
 * 提交编辑表单并等待 PUT 响应。经 SSH 隧道的后端调用偶发在 axios 15s 处中止、
 * 页面观察不到响应：此时先经 API 探测更新是否已落库（已提交则刷新页面收尾），
 * 未提交才安全重试一次（PUT 幂等，重试不会产生第二次写入语义）。
 */
async function submitEditAndAwaitPut(
  page: Page,
  dialog: Locator,
  meetingId: number,
  token: string,
  expectedTitle: string
): Promise<{ status: number; body: string }> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const respPromise = page
      .waitForResponse(
        (r) => r.url().includes(`/api/meetings/${meetingId}`) && r.request().method() === 'PUT',
        { timeout: 60_000 }
      )
      .catch(() => null)
    await dialog.getByRole('button', { name: SAVE_BTN }).click()
    const resp = await respPromise
    if (resp) {
      return { status: resp.status(), body: await resp.text().catch(() => '') }
    }
    // 无响应：探测是否实际已提交
    const probe = await apiFetch<MeetingVO>('GET', `/api/meetings/${meetingId}`, { token })
    if (probe.data?.title === expectedTitle) {
      await page.reload()
      return { status: 200, body: 'committed-but-response-lost' }
    }
  }
  return { status: -1, body: 'no response after 2 attempts' }
}

test.describe('meeting-lifecycle', () => {
  test('修改会议：未开始全量编辑成功；已开始仅标题/说明可编辑（改时间 40909）', async ({
    page
  }) => {
    // 两段流程 + 两次 UI 登录 + 隧道往返，放宽预算
    test.setTimeout(300_000)
    const organizer = uniqName('e2e_lc')
    const tokens = await apiRegister(organizer)
    const roomA = await pickRoom(tokens.accessToken)
    const roomsResp = await apiFetch<Room[]>('GET', '/api/rooms', {
      token: tokens.accessToken,
      params: { enabled: true }
    })
    const roomB = (roomsResp.data ?? []).find((r) => r.enabled && r.id !== roomA.id)
    expect(roomB, '需要至少两个启用会议室以覆盖“修改房间”').toBeTruthy()

    // ---- 未开始会议：UI 编辑标题 + 说明 + 房间 + 时间 ----
    const slotA = await findFreeSlot(tokens.accessToken, roomA.id, 30)
    const title = `E2E编辑 ${uniqName('m')}`
    const m = await createMeetingApi(tokens.accessToken, roomA.id, slotA.start, slotA.end, title)

    await uiLogin(page, organizer)
    await page.goto(`/meetings/${m.id}`)
    await expect(page.locator('h2', { hasText: title })).toBeVisible()

    await page.getByRole('button', { name: EDIT_BTN }).click()
    const dialog = page.locator('.el-dialog:visible')
    await expect(dialog).toBeVisible()
    const newTitle = `${title}-改`
    await dialog.locator('input[placeholder="请输入会议标题"]').fill(newTitle)
    await dialog.locator('textarea').fill('更新后的会议说明')
    await dialog.locator('.el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: roomB!.name }).click()
    const slotB = await findFreeSlot(tokens.accessToken, roomB!.id, 45)
    await fillTimeRange(page, slotB.start, slotB.end)
    const putResp = await submitEditAndAwaitPut(
      page,
      dialog,
      m.id,
      tokens.accessToken,
      newTitle
    )
    expect(putResp.status, `PUT /api/meetings/${m.id} -> ${putResp.status} ${putResp.body}`).toBe(
      200
    )
    await expect(dialog).not.toBeVisible()
    await expect(page.locator('h2', { hasText: newTitle })).toBeVisible()
    await expect(page.locator('.desc')).toContainText(roomB!.name)
    await expect(page.locator('.desc')).toContainText(beijingMinute(slotB.start))
    await expect(page.locator('.desc')).toContainText(beijingMinute(slotB.end))
    await expect(page.locator('.desc')).toContainText('更新后的会议说明')
    // API 复核落库结果
    const after = await apiFetch<MeetingVO>('GET', `/api/meetings/${m.id}`, {
      token: tokens.accessToken
    })
    expect(after.data?.roomId).toBe(roomB!.id)
    expect(after.data?.startTime).toBe(beijingIso(slotB.start))
    expect(after.data?.endTime).toBe(beijingIso(slotB.end))

    // ---- 已开始会议：房间/时间锁定，仅标题/说明可编辑 ----
    const slotC = await findFreeSlot(tokens.accessToken, roomA.id, 30)
    const title2 = `E2E进行中编辑 ${uniqName('m')}`
    const m2 = await createMeetingApi(
      tokens.accessToken,
      roomA.id,
      slotC.start,
      slotC.end,
      title2
    )
    // 拨回开始时间造“进行中”（end_time 保持未来原值，满足 start<=now<end）
    dbSetMeetingTimes(m2.id, new Date(Date.now() - 10 * 60_000), slotC.end)

    await page.goto(`/meetings/${m2.id}`)
    await expect(page.locator('.el-tag', { hasText: '进行中' }).first()).toBeVisible()
    await page.getByRole('button', { name: EDIT_BTN }).click()
    const dialog2 = page.locator('.el-dialog:visible')
    await expect(
      dialog2.locator('.el-alert', { hasText: '会议已开始，仅可修改会议标题与说明' })
    ).toBeVisible()
    // EP 2.x：禁用态类在内部 .el-select__wrapper 上；范围输入框则落 disabled 属性
    await expect(dialog2.locator('.el-select__wrapper')).toHaveClass(/is-disabled/)
    await expect(dialog2.locator('.el-range-input').nth(0)).toBeDisabled()
    await expect(dialog2.locator('.el-range-input').nth(1)).toBeDisabled()
    const newTitle2 = `${title2}-改`
    await dialog2.locator('input[placeholder="请输入会议标题"]').fill(newTitle2)
    await dialog2.locator('textarea').fill('进行中更新说明')
    const put2 = await submitEditAndAwaitPut(
      page,
      dialog2,
      m2.id,
      tokens.accessToken,
      newTitle2
    )
    expect(
      put2.status,
      `PUT started /api/meetings/${m2.id} -> ${put2.status} ${put2.body}`
    ).toBe(200)
    await expect(page.locator('h2', { hasText: newTitle2 })).toBeVisible()
    await expect(page.locator('.desc')).toContainText('进行中更新说明')

    // API 负例：已开始会议提交不同房间/时间 -> 40909 STATE_NOT_ALLOWED
    const badUpdate = await apiFetch('PUT', `/api/meetings/${m2.id}`, {
      token: tokens.accessToken,
      data: {
        title: newTitle2,
        description: null,
        roomId: roomA.id,
        startTime: beijingIso(new Date(Date.now() + 48 * 3600 * 1000)),
        endTime: beijingIso(new Date(Date.now() + 49 * 3600 * 1000))
      }
    })
    expect(badUpdate.status, `PUT started change-time: ${JSON.stringify(badUpdate.raw)}`).toBe(409)
    expect(badUpdate.code).toBe(40909)

    // 清理：逻辑删除，释放占用时段
    const d1 = await apiFetch('DELETE', `/api/meetings/${m.id}`, { token: tokens.accessToken })
    expect(d1.status).toBe(200)
    const d2 = await apiFetch('DELETE', `/api/meetings/${m2.id}`, { token: tokens.accessToken })
    expect(d2.status).toBe(200)
  })

  test('取消会议：确认后标记已取消，报名入口消失，分享链接仍可见已取消', async ({
    page,
    browser
  }) => {
    test.setTimeout(240_000)
    const organizer = uniqName('e2e_lc')
    const viewer = uniqName('e2e_lc')
    const orgTokens = await apiRegister(organizer)
    const viewTokens = await apiRegister(viewer)
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)
    const title = `E2E取消 ${uniqName('m')}`
    const m = await createMeetingApi(orgTokens.accessToken, room.id, slot.start, slot.end, title)

    await uiLogin(page, organizer)
    await page.goto(`/meetings/${m.id}`)
    await expect(page.locator('h2', { hasText: title })).toBeVisible()

    // ConfirmDialog 确认取消
    await page.getByRole('button', { name: CANCEL_BTN }).click()
    const confirm = page.locator('.el-dialog:visible')
    await expect(confirm).toContainText('取消后占用时段将被释放')
    const cancelRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/meetings/${m.id}/cancel`) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await confirm.getByRole('button', { name: CANCEL_BTN }).click()
    const cancelResp = await cancelRespPromise
    expect(
      cancelResp.status(),
      `cancel -> ${cancelResp.status()} ${await cancelResp.text()}`
    ).toBe(200)

    // 详情页：状态已取消，管理操作收敛
    await expect(page.locator('.el-tag', { hasText: '已取消' }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: CANCEL_BTN })).not.toBeVisible()
    await expect(page.getByRole('button', { name: EDIT_BTN })).not.toBeVisible()

    // 原分享链接：第二用户仍可见详情，但报名入口消失并提示已取消
    const ctx2 = await browser.newContext()
    const page2 = await ctx2.newPage()
    await uiLogin(page2, viewer)
    await page2.goto(`/meetings/${m.id}`)
    await expect(page2.locator('h2', { hasText: title })).toBeVisible()
    await expect(page2.locator('.el-tag', { hasText: '已取消' }).first()).toBeVisible()
    await expect(page2.locator('.block-alert')).toContainText('会议已取消，无法报名')
    await expect(page2.getByRole('button', { name: JOIN_BTN })).not.toBeVisible()
    await ctx2.close()

    // API 断言：已取消会议报名 -> 40909；时段释放（同房间同时段可再建会）
    const join = await apiFetch('POST', `/api/meetings/${m.id}/participants`, {
      token: viewTokens.accessToken
    })
    expect(join.status, `join cancelled: ${JSON.stringify(join.raw)}`).toBe(409)
    expect(join.code).toBe(40909)
    const reCreate = await apiFetch<MeetingVO>('POST', '/api/meetings', {
      token: viewTokens.accessToken,
      data: {
        title: `E2E取消后复用 ${uniqName('m')}`,
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(reCreate.status, `recreate on freed slot: ${JSON.stringify(reCreate.raw)}`).toBe(201)

    // 清理
    await apiFetch('DELETE', `/api/meetings/${m.id}`, { token: orgTokens.accessToken })
    await apiFetch('DELETE', `/api/meetings/${reCreate.data!.id}`, {
      token: viewTokens.accessToken
    })
  })

  test('删除会议：确认后跳回列表，详情页与 API 均不可见', async ({ page }) => {
    const organizer = uniqName('e2e_lc')
    const tokens = await apiRegister(organizer)
    const room = await pickRoom(tokens.accessToken)
    const slot = await findFreeSlot(tokens.accessToken, room.id, 30)
    const title = `E2E删除 ${uniqName('m')}`
    const m = await createMeetingApi(tokens.accessToken, room.id, slot.start, slot.end, title)

    await uiLogin(page, organizer)
    await page.goto(`/meetings/${m.id}`)
    await expect(page.locator('h2', { hasText: title })).toBeVisible()

    await page.getByRole('button', { name: DELETE_BTN }).click()
    const confirm = page.locator('.el-dialog:visible')
    await expect(confirm).toContainText('删除后会议不再可见且不可恢复')
    const delRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/meetings/${m.id}`) && r.request().method() === 'DELETE',
      { timeout: 60_000 }
    )
    await confirm.getByRole('button', { name: CONFIRM_DELETE }).click()
    const delResp = await delRespPromise
    expect(delResp.status(), `delete -> ${delResp.status()} ${await delResp.text()}`).toBe(200)

    // 删除成功跳回会议列表
    await page.waitForURL(/\/meetings$/, { timeout: 60_000 })

    // 详情页：不存在提示（detailNotFound -> EmptyState）
    await page.goto(`/meetings/${m.id}`)
    await expect(page.locator('.detail-wrap')).toContainText('会议不存在或已删除')

    // API：404 + 列表不可见（DELETED 对一切查询隐藏）
    const get = await apiFetch('GET', `/api/meetings/${m.id}`, { token: tokens.accessToken })
    expect(get.status, `get deleted: ${JSON.stringify(get.raw)}`).toBe(404)
    expect(get.code).toBe(40401)
    const list = await apiFetch<{ records: { id: number }[] }>('GET', '/api/meetings', {
      token: tokens.accessToken,
      params: { size: 100, onlyMine: true }
    })
    expect((list.data?.records ?? []).some((r) => r.id === m.id)).toBe(false)
  })

  test('提前结束：进行中会议确认后标记已提前结束，剩余时段立即释放', async ({ page }) => {
    test.setTimeout(240_000)
    const organizer = uniqName('e2e_lc')
    const tokens = await apiRegister(organizer)
    const room = await pickRoom(tokens.accessToken)
    const slot = await findFreeSlot(tokens.accessToken, room.id, 30)
    const title = `E2E提前结束 ${uniqName('m')}`
    const m = await createMeetingApi(tokens.accessToken, room.id, slot.start, slot.end, title)
    // 造“进行中”：start_time 拨到 5 分钟前，end_time 保持未来原值
    dbSetMeetingTimes(m.id, new Date(Date.now() - 5 * 60_000), slot.end)

    await uiLogin(page, organizer)
    await page.goto(`/meetings/${m.id}`)
    await expect(page.locator('h2', { hasText: title })).toBeVisible()
    await expect(page.locator('.el-tag', { hasText: '进行中' }).first()).toBeVisible()

    await page.getByRole('button', { name: END_EARLY_BTN }).click()
    const confirm = page.locator('.el-dialog:visible')
    await expect(confirm).toContainText('剩余时段将立即释放')
    const endRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${m.id}/end-early`) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await confirm.getByRole('button', { name: END_EARLY_BTN }).click()
    const endResp = await endRespPromise
    expect(
      endResp.status(),
      `end-early -> ${endResp.status()} ${await endResp.text()}`
    ).toBe(200)
    await expect(page.locator('.el-tag', { hasText: '已提前结束' }).first()).toBeVisible()

    // API：ENDED + endedEarly=true
    const detail = await apiFetch<MeetingVO>('GET', `/api/meetings/${m.id}`, {
      token: tokens.accessToken
    })
    expect(detail.data?.status).toBe('ENDED')
    expect(detail.data?.endedEarly).toBe(true)

    // 时段释放：同房间覆盖原时段再建会 -> 201（不再 TIME_CONFLICT）
    const reCreate = await apiFetch<MeetingVO>('POST', '/api/meetings', {
      token: tokens.accessToken,
      data: {
        title: `E2E结束后复用 ${uniqName('m')}`,
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(reCreate.status, `recreate on released slot: ${JSON.stringify(reCreate.raw)}`).toBe(201)

    // 清理
    await apiFetch('DELETE', `/api/meetings/${m.id}`, { token: tokens.accessToken })
    await apiFetch('DELETE', `/api/meetings/${reCreate.data!.id}`, { token: tokens.accessToken })
  })

  test('越权：普通用户访问他人会议无管理入口，生命周期 API 全部 403', async ({ page }) => {
    test.setTimeout(240_000)
    const organizer = uniqName('e2e_lc')
    const outsider = uniqName('e2e_lc')
    const orgTokens = await apiRegister(organizer)
    const outTokens = await apiRegister(outsider)
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)
    const title = `E2E越权 ${uniqName('m')}`
    const m = await createMeetingApi(orgTokens.accessToken, room.id, slot.start, slot.end, title)

    // UI：他人会议详情可见、可报名，但无任何管理按钮
    await uiLogin(page, outsider)
    await page.goto(`/meetings/${m.id}`)
    await expect(page.locator('h2', { hasText: title })).toBeVisible()
    await expect(page.getByRole('button', { name: JOIN_BTN })).toBeVisible()
    for (const re of [EDIT_BTN, CANCEL_BTN, END_EARLY_BTN, DELETE_BTN]) {
      await expect(page.getByRole('button', { name: re })).not.toBeVisible()
    }

    // API 边界：PUT / cancel / end-early / delete 一律 40301
    const put = await apiFetch('PUT', `/api/meetings/${m.id}`, {
      token: outTokens.accessToken,
      data: {
        title: '越权改名',
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(put.status, `PUT outsider: ${JSON.stringify(put.raw)}`).toBe(403)
    expect(put.code).toBe(40301)
    const cancel = await apiFetch('PATCH', `/api/meetings/${m.id}/cancel`, {
      token: outTokens.accessToken
    })
    expect(cancel.status, `cancel outsider: ${JSON.stringify(cancel.raw)}`).toBe(403)
    expect(cancel.code).toBe(40301)
    const endEarly = await apiFetch('PATCH', `/api/meetings/${m.id}/end-early`, {
      token: outTokens.accessToken
    })
    expect(endEarly.status, `end-early outsider: ${JSON.stringify(endEarly.raw)}`).toBe(403)
    const del = await apiFetch('DELETE', `/api/meetings/${m.id}`, {
      token: outTokens.accessToken
    })
    expect(del.status, `delete outsider: ${JSON.stringify(del.raw)}`).toBe(403)

    // 越权尝试均未生效：会议仍为 ACTIVE、标题未变
    const still = await apiFetch<MeetingVO>('GET', `/api/meetings/${m.id}`, {
      token: orgTokens.accessToken
    })
    expect(still.data?.status).toBe('ACTIVE')
    expect(still.data?.title).toBe(title)

    // 清理
    await apiFetch('DELETE', `/api/meetings/${m.id}`, { token: orgTokens.accessToken })
  })

  test('到期自动结束：sweep 置为已结束，参会者收到结束通知', async ({ page }) => {
    // sweep 等待 + MQ 异步通知 + UI 登录，放宽预算
    test.setTimeout(240_000)
    const organizer = uniqName('e2e_lc')
    const joiner = uniqName('e2e_lc')
    const orgTokens = await apiRegister(organizer)
    const joinTokens = await apiRegister(joiner)
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 15)
    const title = `E2E到期结束 ${uniqName('m')}`
    const m = await createMeetingApi(orgTokens.accessToken, room.id, slot.start, slot.end, title)
    const join = await apiFetch('POST', `/api/meetings/${m.id}/participants`, {
      token: joinTokens.accessToken
    })
    expect(join.status, `join: ${JSON.stringify(join.raw)}`).toBe(200)

    // 拨到过去 -> 进入 sweep 候选（后端须以 roomflow.scheduler.interval=2s 启动）
    dbSetMeetingTimes(m.id, new Date(Date.now() - 30 * 60_000), new Date(Date.now() - 60_000))

    // 等 sweep：ACTIVE -> ENDED（正常到期，endedEarly=false）
    await expect(async () => {
      const d = await apiFetch<MeetingVO>('GET', `/api/meetings/${m.id}`, {
        token: orgTokens.accessToken
      })
      expect(d.data?.status, `meeting status: ${JSON.stringify(d.data)}`).toBe('ENDED')
      expect(d.data?.endedEarly).toBe(false)
    }).toPass({ timeout: 60_000, intervals: [2_000, 3_000, 5_000] })

    // 参会者收到 MEETING_ENDED 站内通知（RabbitMQ 异步投递，UI 轮询可见）
    await uiLogin(page, joiner)
    await page.goto('/notifications')
    const card = page
      .locator('.notification-item', { hasText: '会议已结束' })
      .filter({ hasText: title })
    await expect(async () => {
      await page.reload()
      await expect(card).toBeVisible()
    }).toPass({ timeout: 30_000, intervals: [2_000, 3_000, 5_000] })

    // API 断言：参会者与发起人均收到结束通知
    for (const [who, token] of [
      ['joiner', joinTokens.accessToken],
      ['organizer', orgTokens.accessToken]
    ] as const) {
      const notes = await apiFetch<{ records: { type: string; meetingId: number }[] }>(
        'GET',
        '/api/notifications',
        { token, params: { page: 1, size: 50 } }
      )
      const hit = (notes.data?.records ?? []).find(
        (n) => n.type === 'MEETING_ENDED' && n.meetingId === m.id
      )
      expect(hit, `${who} notifications: ${JSON.stringify(notes.data)}`).toBeTruthy()
    }

    // 清理
    await apiFetch('DELETE', `/api/meetings/${m.id}`, { token: orgTokens.accessToken })
  })
})