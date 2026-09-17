// =============================================================================
// STAGING ACCEPTANCE — the remaining acceptance items against the *deployed*
// staging stack, complementing staging-smoke.spec.ts (register -> UI login ->
// create -> second user joins -> organizer notification -> mark-read).
//
// COVERAGE (the acceptance list):
//   1. admin room management: create / edit / disable / enable / delete
//      (UI-driven, API-verified) — test 'admin room management'
//   2. normal user rejected from admin endpoints (UI gate + API 403) —
//      test 'normal user is rejected from admin surface'
//   3-6. meeting create / update / cancel / logical delete —
//      test 'meeting lifecycle: create/update/cancel/delete'
//   7-8. end-early + scheduler auto-end — test 'real-time lifecycle'
//   9. room slot released after cancel / delete / end-early — asserted inline
//      in the lifecycle tests by rebooking the freed interval via the API
//   10. signup / withdraw / rejoin / kick / permanent-ban — test
//      'participant flows'
//   11. in-app notification produced + marked read — asserted in
//      'participant flows' (UI mark-read) and 'real-time lifecycle'
//      (MEETING_ENDED via the real MQ chain)
//   12. permission boundaries organizer vs admin vs normal — asserted in
//      'normal user is rejected...' and 'participant flows' (outsider 403s,
//      admin acting on another user's meeting)
//
// CREDENTIALS — env only, never literals, never logged:
//   STAGING_E2E_PASSWORD   password for accounts this spec registers
//   STAGING_ADMIN_PASSWORD password of the seeded 'admin' account
//   `npm run test:e2e:staging` refuses to start without BOTH (pretest hook).
//
// TIMING — why the 'real-time lifecycle' test takes real wall-clock minutes:
//   the contract forbids creating a meeting whose start is in the past, and
//   the staging database is NOT reachable from the runner (all middleware is
//   loopback-bound on the host; only the frontend port is tunnelled), so the
//   dev-suite trick of rewriting start_time/end_time in MySQL is impossible
//   here. 'In-progress' and 'expired' meetings are made by booking the
//   EARLIEST legal slot — the next 15-minute boundary — and waiting for real
//   time to pass:
//     * end-early: start at the next boundary, wait until it begins
//     * auto-end:  start at the next boundary with the minimum 15-minute
//       duration, wait for the end boundary plus the staging sweep
//       (application-staging.yml sets no override -> the default 60s
//       fixedDelay applies, lockAtLeast PT50S)
//   Both meetings are created up front so the waits overlap; worst case is
//   roughly (time to next boundary) + 15 min + one sweep ~= 35 minutes, and
//   the test carries a matching dedicated timeout. Actual waits are logged.
//
// CLEANUP: every meeting this file creates is cancelled or deleted via the
// API and every room it creates is disabled/deleted. There is no delete-user
// API, so the registered test accounts remain on the shared staging database;
// they are identifiable by the uniqName prefixes (stg_*). See the residual
// note in staging-smoke.spec.ts — it applies unchanged.
// =============================================================================

import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import {
  API_BASE,
  DEV_API_BASE_URL,
  STAGING_ADMIN_PASSWORD,
  STAGING_PASSWORD,
  STAGING_TARGET,
  apiFetch,
  apiLogin,
  apiRegister,
  beijingIso,
  fillTimeRange,
  findFreeSlot,
  pickRoom,
  uiLogin,
  uniqName,
  type ApiResp,
  type Room
} from './helpers'

const CREATE_BTN = /创\s*建\s*会\s*议/
const JOIN_BTN = /报\s*名\s*参\s*会/
const EDIT_BTN = /编\s*辑\s*会\s*议/
const CANCEL_BTN = /取\s*消\s*会\s*议/
const END_EARLY_BTN = /提\s*前\s*结\s*束/
const DELETE_BTN = /删\s*除\s*会\s*议/
const SAVE_BTN = /保\s*存\s*修\s*改/
const LEAVE_BTN = /退\s*出\s*报\s*名/
const MARK_READ = /标\s*记\s*已\s*读/

const QUARTER_MS = 15 * 60 * 1000
// Staging sweep: no override in application-staging.yml -> the @Scheduled
// default fixedDelay of 60s applies. Generous poll budget on top.
const SWEEP_GRACE_MS = 4 * 60 * 1000

interface MeetingVO {
  id: number
  title: string
  roomId: number
  startTime: string
  endTime: string
  status: 'ACTIVE' | 'ENDED' | 'CANCELLED' | 'DELETED'
  endedEarly: boolean
}

interface NotificationVO {
  id: number
  type: string
  meetingId: number | null
  isRead: boolean
  title: string
  content: string
}

// Same guards as staging-smoke.spec.ts: a bare `npx playwright test
// --project=staging` must still fail closed.
test.skip(!STAGING_TARGET, 'staging base URL not configured (set STAGING_BASE_URL)')
test.skip(
  !STAGING_PASSWORD,
  'STAGING_E2E_PASSWORD is not set: this spec creates real accounts on a shared staging database'
)

// ---------- local helpers ----------

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

function getMeeting(token: string, id: number): Promise<ApiResp<MeetingVO>> {
  return apiFetch<MeetingVO>('GET', `/api/meetings/${id}`, { token })
}

/** First 15-minute boundary strictly in the future — the earliest legal start. */
function nextBoundary(fromMs = Date.now()): Date {
  return new Date((Math.floor(fromMs / QUARTER_MS) + 1) * QUARTER_MS)
}

/**
 * Like findFreeSlot but starts scanning at the NEXT quarter boundary instead of
 * now+10min — used when the test wants the smallest possible wait (the meeting
 * must begin/expire in real time on staging, where no DB access exists).
 */
async function earliestFreeSlot(
  token: string,
  roomId: number,
  durationMin: number
): Promise<{ start: Date; end: Date }> {
  const now = Date.now()
  const r = await apiFetch<{ occupiedSlots: { startTime: string; endTime: string }[] }>(
    'GET',
    `/api/rooms/${roomId}/availability`,
    {
      token,
      params: {
        startDate: new Date(now + 8 * 3600 * 1000).toISOString().slice(0, 10),
        endDate: new Date(now + 48 * 3600 * 1000).toISOString().slice(0, 10)
      }
    }
  )
  expect(r.status, `availability: ${JSON.stringify(r.raw)}`).toBe(200)
  const busy = (r.data?.occupiedSlots ?? []).map(
    (s) => [Date.parse(s.startTime), Date.parse(s.endTime)] as const
  )
  for (let t = nextBoundary(now).getTime(); t <= now + 24 * 3600 * 1000; t += QUARTER_MS) {
    const e = t + durationMin * 60 * 1000
    if (!busy.some(([bs, be]) => t < be && e > bs)) {
      return { start: new Date(t), end: new Date(e) }
    }
  }
  throw new Error('no free slot within the next 24h')
}

async function sleep(ms: number): Promise<void> {
  if (ms > 0) await new Promise((r) => setTimeout(r, ms))
}

async function notifications(token: string): Promise<NotificationVO[]> {
  const r = await apiFetch<{ records: NotificationVO[] }>('GET', '/api/notifications', {
    token,
    params: { page: 1, size: 100 }
  })
  expect(r.status, `notifications: ${JSON.stringify(r.raw)}`).toBe(200)
  return r.data?.records ?? []
}

/** A second browser context on the SAME staging target (baseURL is not inherited). */
async function newStagingContext(browser: Browser): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({
    baseURL: STAGING_TARGET,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  return { ctx, page: await ctx.newPage() }
}
test.describe('staging-acceptance', () => {
  test('admin room management: create/edit/disable/enable/delete via UI, verified via API', async ({
    page
  }) => {
    test.setTimeout(240_000)
    test.skip(
      !STAGING_ADMIN_PASSWORD,
      'STAGING_ADMIN_PASSWORD is not set: admin-side coverage cannot run'
    )
    expect(
      API_BASE,
      `API_BASE resolved to the local dev API while the browser targets ${STAGING_TARGET}`
    ).not.toBe(DEV_API_BASE_URL)

    const adminTokens = await apiLogin('admin', STAGING_ADMIN_PASSWORD)
    const roomName = `STG-ACC-${uniqName('r')}`

    await uiLogin(page, 'admin', STAGING_ADMIN_PASSWORD)
    // Admin nav entry exists only for ADMIN.
    await expect(page.locator('.nav-menu')).toContainText('管理')
    await page.goto('/admin/rooms')
    await expect(page.locator('h2', { hasText: '会议室管理' })).toBeVisible()

    // ---- create via UI ----
    await page.getByRole('button', { name: '新建会议室' }).click()
    const dialog = page.locator('.el-dialog:visible')
    await dialog.locator('input[placeholder="请输入会议室名称"]').fill(roomName)
    await dialog.locator('input[placeholder="选填，如 3楼东侧"]').fill('E2E验收')
    await dialog.locator('.el-input-number input').fill('8')
    const createRespPromise = page.waitForResponse(
      (r) => /\/api\/rooms$/.test(r.url()) && r.request().method() === 'POST',
      { timeout: 60_000 }
    )
    await dialog.getByRole('button', { name: '创建会议室' }).click()
    expect((await createRespPromise).status(), 'POST /api/rooms via UI').toBe(201)
    const row = page.locator('.el-table__row', { hasText: roomName })
    await expect(row).toBeVisible()

    // ---- API cross-check ----
    const rooms = await apiFetch<Room[]>('GET', '/api/rooms', { token: adminTokens.accessToken })
    const created = (rooms.data ?? []).find((r) => r.name === roomName)
    expect(created, `room ${roomName} in GET /api/rooms`).toBeTruthy()
    const roomId = created!.id
    expect(created!.enabled).toBe(true)

    // ---- edit via UI ----
    await row.getByRole('button', { name: '编辑' }).click()
    const editDialog = page.locator('.el-dialog:visible')
    const renamed = `${roomName}-改`
    await editDialog.locator('input[placeholder="请输入会议室名称"]').fill(renamed)
    const putRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/rooms/${roomId}`) && r.request().method() === 'PUT',
      { timeout: 60_000 }
    )
    await editDialog.getByRole('button', { name: SAVE_BTN }).click()
    expect((await putRespPromise).status(), 'PUT /api/rooms/{id} via UI').toBe(200)
    const row2 = page.locator('.el-table__row', { hasText: renamed })
    await expect(row2).toBeVisible()

    // ---- disable via UI (destructive -> ConfirmDialog), verify via API ----
    await row2.getByRole('button', { name: '停用' }).click()
    const confirmDisable = page.locator('.el-dialog:visible')
    await expect(confirmDisable).toContainText('停用')
    const disableRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/rooms/${roomId}/status`) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await confirmDisable.getByRole('button', { name: '停用' }).click()
    expect((await disableRespPromise).status(), 'PATCH status disable').toBe(200)
    await expect(row2.locator('.el-tag', { hasText: '停用' })).toBeVisible()
    const disabledGet = await apiFetch<Room>('GET', `/api/rooms/${roomId}`, {
      token: adminTokens.accessToken
    })
    expect(disabledGet.data?.enabled).toBe(false)

    // A disabled room rejects new bookings (40906 ROOM_DISABLED) — the enabled
    // check runs before the conflict check, so any legal window suffices.
    const s = nextBoundary()
    const reject = await apiFetch('POST', '/api/meetings', {
      token: adminTokens.accessToken,
      data: {
        title: `STG验收-停用房间 ${uniqName('m')}`,
        roomId,
        startTime: beijingIso(s),
        endTime: beijingIso(new Date(s.getTime() + QUARTER_MS))
      }
    })
    expect(reject.status, `book disabled room: ${JSON.stringify(reject.raw)}`).toBe(409)
    expect(reject.code).toBe(40906)

    // ---- enable via UI (non-destructive -> no confirm) ----
    const enableRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/rooms/${roomId}/status`) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await row2.getByRole('button', { name: '启用' }).click()
    expect((await enableRespPromise).status(), 'PATCH status enable').toBe(200)
    await expect(row2.locator('.el-tag', { hasText: '启用' })).toBeVisible()

    // ---- cleanup: delete (soft delete -> disabled) via UI ----
    await row2.getByRole('button', { name: '删除' }).click()
    const confirmDelete = page.locator('.el-dialog:visible')
    const delRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/rooms/${roomId}`) && r.request().method() === 'DELETE',
      { timeout: 60_000 }
    )
    await confirmDelete.getByRole('button', { name: '删除' }).click()
    expect((await delRespPromise).status(), 'DELETE /api/rooms/{id}').toBe(200)
    const deletedGet = await apiFetch<Room>('GET', `/api/rooms/${roomId}`, {
      token: adminTokens.accessToken
    })
    expect(deletedGet.data?.enabled).toBe(false)
  })

  test('normal user is rejected from the admin surface (UI gate + API 403)', async ({ page }) => {
    test.setTimeout(180_000)
    const username = uniqName('stg_perm')
    const tokens = await apiRegister(username, STAGING_PASSWORD)
    await uiLogin(page, username, STAGING_PASSWORD)

    // No admin entry in the nav; the route guard bounces a direct visit.
    await expect(page.locator('.nav-menu')).not.toContainText('管理')
    await page.goto('/admin/rooms')
    await expect(page).toHaveURL(/\/meetings$/)

    // Every ADMIN-only endpoint rejects with 403.
    const attempts = [
      apiFetch('POST', '/api/rooms', {
        token: tokens.accessToken,
        data: { name: `STG越权 ${uniqName('r')}`, capacity: 4 }
      }),
      apiFetch('PUT', '/api/rooms/1', {
        token: tokens.accessToken,
        data: { name: 'x', capacity: 4 }
      }),
      apiFetch('PATCH', '/api/rooms/1/status', {
        token: tokens.accessToken,
        data: { enabled: false }
      }),
      apiFetch('DELETE', '/api/rooms/1', { token: tokens.accessToken })
    ]
    for (const call of attempts) {
      const r = await call
      expect(r.status, `admin endpoint as normal user: ${JSON.stringify(r.raw)}`).toBe(403)
    }
  })
  test('meeting lifecycle: UI create -> update -> cancel -> slot release -> logical delete', async ({
    page
  }) => {
    test.setTimeout(300_000)
    const organizer = uniqName('stg_lc')
    const orgTokens = await apiRegister(organizer, STAGING_PASSWORD)
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)
    const title = `STG验收 ${uniqName('m')}`

    // ---- create via UI ----
    await uiLogin(page, organizer, STAGING_PASSWORD)
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
    expect(createResp?.status(), 'POST /api/meetings via UI').toBe(201)
    await page.waitForURL(/\/meetings\/\d+/, { timeout: 60_000 })
    const meetingId = Number(page.url().match(/\/meetings\/(\d+)/)![1])
    await expect(page.locator('h2', { hasText: title })).toBeVisible()

    // ---- update a not-yet-started meeting via UI ----
    const renamed = `${title}-改`
    await page.getByRole('button', { name: EDIT_BTN }).click()
    const editDialog = page.locator('.el-dialog:visible')
    await editDialog.locator('input[placeholder="请输入会议标题"]').fill(renamed)
    const putRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/meetings/${meetingId}`) && r.request().method() === 'PUT',
      { timeout: 60_000 }
    )
    await editDialog.getByRole('button', { name: SAVE_BTN }).click()
    expect((await putRespPromise).status(), 'PUT /api/meetings/{id} via UI').toBe(200)
    await expect(page.locator('h2', { hasText: renamed })).toBeVisible()
    const afterEdit = await getMeeting(orgTokens.accessToken, meetingId)
    expect(afterEdit.data?.title).toBe(renamed)

    // ---- cancel via UI ----
    await page.getByRole('button', { name: CANCEL_BTN }).click()
    const confirmCancel = page.locator('.el-dialog:visible')
    await expect(confirmCancel).toContainText('取消后占用时段将被释放')
    const cancelRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${meetingId}/cancel`) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await confirmCancel.getByRole('button', { name: CANCEL_BTN }).click()
    expect((await cancelRespPromise).status(), 'PATCH cancel via UI').toBe(200)
    await expect(page.locator('.el-tag', { hasText: '已取消' }).first()).toBeVisible()

    // ---- the cancelled meeting's slot is released (rebook via API -> 201) ----
    const rebooked = await apiFetch<MeetingVO>('POST', '/api/meetings', {
      token: orgTokens.accessToken,
      data: {
        title: `STG验收-复用 ${uniqName('m')}`,
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(rebooked.status, `rebook freed slot: ${JSON.stringify(rebooked.raw)}`).toBe(201)
    // A logical delete releases the slot the same way: delete the rebooking and
    // book the identical interval once more.
    const delRebooked = await apiFetch('DELETE', `/api/meetings/${rebooked.data!.id}`, {
      token: orgTokens.accessToken
    })
    expect(delRebooked.status, 'delete rebooked meeting').toBe(200)
    const rebookAgain = await apiFetch<MeetingVO>('POST', '/api/meetings', {
      token: orgTokens.accessToken,
      data: {
        title: `STG验收-复用2 ${uniqName('m')}`,
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(rebookAgain.status, 'rebook after delete releases the slot too').toBe(201)
    await apiFetch('DELETE', `/api/meetings/${rebookAgain.data!.id}`, {
      token: orgTokens.accessToken
    })

    // ---- logical delete via UI: invisible afterwards ----
    await page.goto(`/meetings/${meetingId}`)
    await page.getByRole('button', { name: DELETE_BTN }).click()
    const confirmDelete = page.locator('.el-dialog:visible')
    await expect(confirmDelete).toContainText('删除后会议不再可见且不可恢复')
    const delRespPromise = page.waitForResponse(
      (r) => r.url().includes(`/api/meetings/${meetingId}`) && r.request().method() === 'DELETE',
      { timeout: 60_000 }
    )
    await confirmDelete.getByRole('button', { name: /删\s*除/ }).click()
    expect((await delRespPromise).status(), 'DELETE meeting via UI').toBe(200)
    await page.waitForURL(/\/meetings$/, { timeout: 60_000 })
    const gone = await getMeeting(orgTokens.accessToken, meetingId)
    expect(gone.status, `GET deleted meeting: ${JSON.stringify(gone.raw)}`).toBe(404)
    expect(gone.code).toBe(40401)
  })
  test('participant flows: signup/withdraw/rejoin/kick/permanent-ban + notification mark-read + boundaries', async ({
    page,
    browser
  }) => {
    test.setTimeout(300_000)
    const organizer = uniqName('stg_porg')
    const joiner = uniqName('stg_pjoin')
    const outsider = uniqName('stg_pout')
    const orgTokens = await apiRegister(organizer, STAGING_PASSWORD)
    const joinTokens = await apiRegister(joiner, STAGING_PASSWORD)
    const outTokens = await apiRegister(outsider, STAGING_PASSWORD)
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)
    const title = `STG参会 ${uniqName('m')}`
    const m = await createMeetingApi(orgTokens.accessToken, room.id, slot.start, slot.end, title)

    // ---- joiner: UI login -> signup -> withdraw (leave) -> rejoin via API ----
    const { ctx: joinerCtx, page: joinerPage } = await newStagingContext(browser)
    await uiLogin(joinerPage, joiner, STAGING_PASSWORD)
    await joinerPage.goto(`/meetings/${m.id}`)
    await expect(joinerPage.locator('h2', { hasText: title })).toBeVisible()
    const joinRespPromise = joinerPage.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${m.id}/participants`) &&
        r.request().method() === 'POST',
      { timeout: 60_000 }
    )
    await joinerPage.getByRole('button', { name: JOIN_BTN }).click()
    expect((await joinRespPromise).status(), 'join via UI').toBe(200)
    const joinerRow = joinerPage.locator('.participant-table .el-table__row', { hasText: joiner })
    await expect(joinerRow.locator('.el-tag', { hasText: '参会中' })).toBeVisible()

    // withdraw (退出报名) via UI
    await joinerPage.getByRole('button', { name: LEAVE_BTN }).click()
    const leaveConfirm = joinerPage.locator('.el-dialog:visible')
    const leaveRespPromise = joinerPage.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${m.id}/participants/me`) &&
        r.request().method() === 'DELETE',
      { timeout: 60_000 }
    )
    await leaveConfirm.getByRole('button', { name: LEAVE_BTN }).click()
    expect((await leaveRespPromise).status(), 'leave via UI').toBe(200)
    await expect(joinerRow.locator('.el-tag', { hasText: '已退出' })).toBeVisible()

    // rejoin after a voluntary leave is allowed (API)
    const rejoin = await apiFetch('POST', `/api/meetings/${m.id}/participants`, {
      token: joinTokens.accessToken
    })
    expect(rejoin.status, `rejoin: ${JSON.stringify(rejoin.raw)}`).toBe(200)

    // ---- organizer kicks the joiner via UI -> permanent ban ----
    await uiLogin(page, organizer, STAGING_PASSWORD)
    await page.goto(`/meetings/${m.id}`)
    const orgRow = page.locator('.participant-table .el-table__row', { hasText: joiner })
    await expect(orgRow.locator('.el-tag', { hasText: '参会中' })).toBeVisible()
    await orgRow.getByRole('button', { name: '移出' }).click()
    const kickConfirm = page.locator('.el-dialog:visible')
    const kickRespPromise = page.waitForResponse(
      (r) =>
        new RegExp(`/api/meetings/${m.id}/participants/\\d+$`).test(r.url()) &&
        r.request().method() === 'DELETE',
      { timeout: 60_000 }
    )
    await kickConfirm.getByRole('button', { name: '移出' }).click()
    expect((await kickRespPromise).status(), 'kick via UI').toBe(200)
    await expect(orgRow.locator('.el-tag', { hasText: '已被移出' })).toBeVisible()

    // permanent ban: a kicked user can never rejoin (40904 JOIN_BANNED)
    const bannedJoin = await apiFetch('POST', `/api/meetings/${m.id}/participants`, {
      token: joinTokens.accessToken
    })
    expect(bannedJoin.status, `rejoin after kick: ${JSON.stringify(bannedJoin.raw)}`).toBe(409)
    expect(bannedJoin.code).toBe(40904)

    // ---- notifications: join + leave reached the organizer (MQ, async) ----
    await expect(async () => {
      const notes = await notifications(orgTokens.accessToken)
      const joined = notes.find((n) => n.type === 'PARTICIPANT_JOINED' && n.meetingId === m.id)
      const left = notes.find((n) => n.type === 'PARTICIPANT_LEFT' && n.meetingId === m.id)
      expect(joined, `organizer notifications: ${JSON.stringify(notes)}`).toBeTruthy()
      expect(left).toBeTruthy()
    }).toPass({ timeout: 60_000, intervals: [2_000, 3_000, 5_000] })
    // the kicked participant is notified too
    await expect(async () => {
      const notes = await notifications(joinTokens.accessToken)
      const kicked = notes.find((n) => n.type === 'PARTICIPANT_KICKED' && n.meetingId === m.id)
      expect(kicked, `joiner notifications: ${JSON.stringify(notes)}`).toBeTruthy()
    }).toPass({ timeout: 60_000, intervals: [2_000, 3_000, 5_000] })

    // ---- mark-read through the real notifications UI ----
    await page.goto('/notifications')
    const card = page.locator('.notification-item', { hasText: title }).first()
    await expect(card).toBeVisible()
    await expect(card).toHaveClass(/unread/)
    const readRespPromise = page.waitForResponse(
      (r) =>
        /\/api\/notifications\/\d+\/read/.test(r.url()) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await card.getByRole('button', { name: MARK_READ }).click()
    expect((await readRespPromise).status(), 'mark read via UI').toBe(200)
    await expect(card).not.toHaveClass(/unread/)

    // ---- permission boundaries on someone else's meeting ----
    const joinerId = (
      await apiFetch<{ id: number }>('GET', '/api/auth/me', { token: joinTokens.accessToken })
    ).data!.id
    const outsiderPut = await apiFetch('PUT', `/api/meetings/${m.id}`, {
      token: outTokens.accessToken,
      data: {
        title: '越权改名',
        roomId: room.id,
        startTime: beijingIso(slot.start),
        endTime: beijingIso(slot.end)
      }
    })
    expect(outsiderPut.status, `outsider PUT: ${JSON.stringify(outsiderPut.raw)}`).toBe(403)
    const outsiderCancel = await apiFetch('PATCH', `/api/meetings/${m.id}/cancel`, {
      token: outTokens.accessToken
    })
    expect(outsiderCancel.status, 'outsider cancel').toBe(403)
    const outsiderEnd = await apiFetch('PATCH', `/api/meetings/${m.id}/end-early`, {
      token: outTokens.accessToken
    })
    expect(outsiderEnd.status, 'outsider end-early').toBe(403)
    const outsiderKick = await apiFetch(
      'DELETE',
      `/api/meetings/${m.id}/participants/${joinerId}`,
      { token: outTokens.accessToken }
    )
    expect(outsiderKick.status, 'outsider kick').toBe(403)
    const outsiderDel = await apiFetch('DELETE', `/api/meetings/${m.id}`, {
      token: outTokens.accessToken
    })
    expect(outsiderDel.status, 'outsider delete').toBe(403)

    // outsider UI: detail readable, no manage buttons
    const { ctx: outCtx, page: outPage } = await newStagingContext(browser)
    await uiLogin(outPage, outsider, STAGING_PASSWORD)
    await outPage.goto(`/meetings/${m.id}`)
    await expect(outPage.locator('h2', { hasText: title })).toBeVisible()
    for (const re of [EDIT_BTN, CANCEL_BTN, END_EARLY_BTN, DELETE_BTN]) {
      await expect(outPage.getByRole('button', { name: re })).not.toBeVisible()
    }
    await outCtx.close()
    await joinerCtx.close()

    // ---- admin may manage another user's meeting; use it as cleanup ----
    if (STAGING_ADMIN_PASSWORD) {
      const adminTokens = await apiLogin('admin', STAGING_ADMIN_PASSWORD)
      const adminDel = await apiFetch('DELETE', `/api/meetings/${m.id}`, {
        token: adminTokens.accessToken
      })
      expect(
        adminDel.status,
        `admin delete others' meeting: ${JSON.stringify(adminDel.raw)}`
      ).toBe(200)
    } else {
      const del = await apiFetch('DELETE', `/api/meetings/${m.id}`, {
        token: orgTokens.accessToken
      })
      expect(del.status, 'cleanup delete').toBe(200)
    }
  })
  test('real-time lifecycle: in-progress end-early + scheduler auto-end on the real staging sweep', async ({
    page
  }) => {
    // Booking the earliest legal slot (the next 15-min boundary) and waiting
    // for real time to pass: worst case ~35 min. See the file header.
    test.setTimeout(45 * 60_000)
    const t0 = Date.now()
    const organizer = uniqName('stg_rt')
    const joiner = uniqName('stg_rtj')
    const orgTokens = await apiRegister(organizer, STAGING_PASSWORD)
    const joinTokens = await apiRegister(joiner, STAGING_PASSWORD)

    // Two distinct rooms so the two boundary meetings cannot conflict.
    const roomsResp = await apiFetch<Room[]>('GET', '/api/rooms', {
      token: orgTokens.accessToken,
      params: { enabled: true }
    })
    const enabled = (roomsResp.data ?? []).filter((r) => r.enabled)
    const roomA = enabled[0]
    let roomB = enabled[1]
    let scratchRoomId: number | null = null
    if (!roomA) throw new Error('no enabled rooms on staging')
    if (!roomB) {
      test.skip(
        !STAGING_ADMIN_PASSWORD,
        'only one enabled room on staging and no STAGING_ADMIN_PASSWORD to create another'
      )
      const adminTokens = await apiLogin('admin', STAGING_ADMIN_PASSWORD)
      const made = await apiFetch<Room>('POST', '/api/rooms', {
        token: adminTokens.accessToken,
        data: { name: `STG-RT-${uniqName('r')}`, capacity: 10, location: 'E2E' }
      })
      expect(made.status, `create scratch room: ${JSON.stringify(made.raw)}`).toBe(201)
      roomB = made.data!
      scratchRoomId = roomB.id
    }

    const slotA = await earliestFreeSlot(orgTokens.accessToken, roomA.id, 45)
    const slotB = await earliestFreeSlot(orgTokens.accessToken, roomB.id, 15)
    const titleA = `STG提前结束 ${uniqName('m')}`
    const titleB = `STG到期结束 ${uniqName('m')}`
    const mA = await createMeetingApi(
      orgTokens.accessToken,
      roomA.id,
      slotA.start,
      slotA.end,
      titleA
    )
    const mB = await createMeetingApi(
      orgTokens.accessToken,
      roomB.id,
      slotB.start,
      slotB.end,
      titleB
    )
    // The joiner must sign up while startTime is still in the future.
    const join = await apiFetch('POST', `/api/meetings/${mB.id}/participants`, {
      token: joinTokens.accessToken
    })
    expect(join.status, `join mB: ${JSON.stringify(join.raw)}`).toBe(200)

    // ---- wait until meeting A is actually in progress (real time) ----
    const waitStart = slotA.start.getTime() + 5_000 - Date.now()
    console.log(
      `[staging-acceptance] waiting ${(waitStart / 1000).toFixed(0)}s for meeting A ` +
        `to reach its start boundary (${slotA.start.toISOString()})`
    )
    await sleep(waitStart)

    // JWT_ACCESS_TTL is 15m: the boundary wait above may already have outlived
    // the tokens minted at test start — re-login before any API call below.
    const orgMid = await apiLogin(organizer, STAGING_PASSWORD)

    // ---- end-early via the real UI ----
    await uiLogin(page, organizer, STAGING_PASSWORD)
    await page.goto(`/meetings/${mA.id}`)
    await expect(page.locator('.el-tag', { hasText: '进行中' }).first()).toBeVisible()
    await page.getByRole('button', { name: END_EARLY_BTN }).click()
    const confirmEnd = page.locator('.el-dialog:visible')
    const endRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${mA.id}/end-early`) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await confirmEnd.getByRole('button', { name: END_EARLY_BTN }).click()
    expect((await endRespPromise).status(), 'end-early via UI').toBe(200)
    await expect(page.locator('.el-tag', { hasText: '已提前结束' }).first()).toBeVisible()
    const endedA = await getMeeting(orgMid.accessToken, mA.id)
    expect(endedA.data?.status).toBe('ENDED')
    expect(endedA.data?.endedEarly).toBe(true)

    // The released tail is bookable again: a legal future slot overlapping the
    // old meeting's remaining interval proves the release (the part in the past
    // cannot be rebooked by definition).
    const tail = await earliestFreeSlot(orgMid.accessToken, roomA.id, 15)
    expect(tail.start.getTime() < slotA.end.getTime()).toBe(true)
    const rebooked = await createMeetingApi(
      orgMid.accessToken,
      roomA.id,
      tail.start,
      tail.end,
      `STG提前结束-复用 ${uniqName('m')}`
    )

    // ---- auto-end: wait for B's end boundary + the real staging sweep ----
    const waitEnd = slotB.end.getTime() - Date.now()
    if (waitEnd > 0) {
      console.log(
        `[staging-acceptance] waiting ${(waitEnd / 1000).toFixed(0)}s for meeting B ` +
          `to reach its end boundary (${slotB.end.toISOString()})`
      )
      await sleep(waitEnd)
    }
    // The real-time waits above have definitely outlived JWT_ACCESS_TTL (15m),
    // so every API call below uses freshly minted tokens — polling with an
    // expired token surfaces as data:null, indistinguishable from "not ENDED".
    const orgEnd = await apiLogin(organizer, STAGING_PASSWORD)
    const joinEnd = await apiLogin(joiner, STAGING_PASSWORD)
    const sweepDeadline = Date.now() + SWEEP_GRACE_MS
    await expect(async () => {
      expect(
        Date.now() < sweepDeadline,
        `meeting B still not ENDED ${((Date.now() - slotB.end.getTime()) / 1000).toFixed(0)}s ` +
          'after its end boundary (staging sweep interval is the default 60s)'
      ).toBe(true)
      const d = await getMeeting(orgEnd.accessToken, mB.id)
      expect(d.data?.status, `mB status: ${JSON.stringify(d.data)}`).toBe('ENDED')
    }).toPass({ timeout: SWEEP_GRACE_MS, intervals: [10_000, 15_000, 30_000] })
    const endedB = await getMeeting(orgEnd.accessToken, mB.id)
    expect(endedB.data?.endedEarly).toBe(false)
    console.log(
      `[staging-acceptance] auto-end observed ` +
        `${((Date.now() - slotB.end.getTime()) / 1000).toFixed(0)}s after the end ` +
        `boundary; total test wait ${((Date.now() - t0) / 1000).toFixed(0)}s`
    )

    // MEETING_ENDED reaches every active participant over the real MQ chain.
    await expect(async () => {
      const orgNotes = await notifications(orgEnd.accessToken)
      const joinNotes = await notifications(joinEnd.accessToken)
      expect(
        orgNotes.find((n) => n.type === 'MEETING_ENDED' && n.meetingId === mB.id),
        'organizer MEETING_ENDED'
      ).toBeTruthy()
      expect(
        joinNotes.find((n) => n.type === 'MEETING_ENDED' && n.meetingId === mB.id),
        'joiner MEETING_ENDED'
      ).toBeTruthy()
      // ...and for the manually end-early meeting as well.
      expect(
        orgNotes.find((n) => n.type === 'MEETING_ENDED' && n.meetingId === mA.id),
        'organizer MEETING_ENDED (end-early)'
      ).toBeTruthy()
    }).toPass({ timeout: 60_000, intervals: [2_000, 3_000, 5_000] })

    // ---- cleanup: delete every meeting this test created; scratch room off ----
    for (const id of [mA.id, mB.id, rebooked.id]) {
      const d = await apiFetch('DELETE', `/api/meetings/${id}`, { token: orgEnd.accessToken })
      expect(d.status, `cleanup delete meeting ${id}`).toBe(200)
    }
    if (scratchRoomId !== null) {
      const adminTokens = await apiLogin('admin', STAGING_ADMIN_PASSWORD)
      await apiFetch('DELETE', `/api/rooms/${scratchRoomId}`, { token: adminTokens.accessToken })
    }
  })
})