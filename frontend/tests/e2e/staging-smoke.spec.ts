// =============================================================================
// STAGING SMOKE — the core acceptance flow against a *deployed* staging stack.
//
// WHAT THIS IS
//   The one end-to-end flow that has to work on staging: register a fresh user
//   -> log in through the real auth UI -> create a meeting -> a second user
//   signs up (报名) -> the organizer receives the signup notification -> marks
//   it read. It expects a live, already-running stack: no dev server, no mocks,
//   no webServer block. Playwright drives a browser against whatever
//   PLAYWRIGHT_BASE_URL / STAGING_BASE_URL points at, and the helpers in
//   ./helpers.ts derive their API base from that same variable (the deployed
//   stack serves the SPA and /api/ from one origin - see docker/nginx.conf), so
//   the UI and the API can never end up on two different environments.
//
// *** LIMITATION — THIS SPEC CANNOT BE RUN OR VERIFIED IN THIS ENVIRONMENT ***
//   No staging host is provisioned yet; creating one is separate work. Nothing
//   here has ever executed against a real staging URL. It is written so it will
//   work the moment such a host exists, and it is skipped (and not collected by
//   the default `chromium` project at all) until then. To run it, two things
//   must be provided:
//     1. a BROWSER-REACHABLE staging frontend URL (STAGING_BASE_URL or
//        PLAYWRIGHT_BASE_URL). The stack's middleware ports are bound to
//        loopback, so this must be the externally reachable URL - not
//        127.0.0.1:<port>;
//     2. STAGING_E2E_PASSWORD - a password that is NOT in this repository (see
//        CREDENTIALS below). STAGING_API_BASE_URL is only needed if the API is
//        reachable somewhere other than the staging origin.
//
// HOW TO RUN (once a staging host exists)
//   cd frontend
//   STAGING_BASE_URL=https://staging.example.com \
//   STAGING_E2E_PASSWORD=<a password that lives outside this repo> \
//   npm run test:e2e:staging
//   (the same as: npx playwright test --project=staging.
//    npm's `pretest:e2e:staging` hook checks the environment first and fails
//    with an actionable message, rather than Playwright reporting
//    `Project(s) "staging" not found` when no staging URL is configured.)
//
// CREDENTIALS — why STAGING_E2E_PASSWORD exists
//   This spec registers REAL accounts on a shared, publicly reachable staging
//   database, and the project has no delete-user API, so those accounts remain
//   usable afterwards (see RESIDUAL EXPOSURE). The local-dev default password
//   (helpers.TEST_PASSWORD) is committed to this public repository, so reusing
//   it here would leave login-capable accounts whose credentials are world
//   known - and organizers can read participant data. The staging password is
//   therefore supplied from the environment only: without STAGING_E2E_PASSWORD
//   this file skips, and `npm run test:e2e:staging` refuses to start.
//
// GUARD
//   Skipped unless BOTH a staging URL and STAGING_E2E_PASSWORD are set, so it
//   can never execute against the local dev stack or during a normal CI run.
//
// SHARED DATA
//   Staging data is a REAL SHARED DATABASE, not a per-run fixture: other runs,
//   manual testing and seeded demo data all live in it. This spec therefore
//   assumes nothing about emptiness and names every entity uniquely (uniqName)
//   so it neither collides with nor gets broken by whatever is already there.
//   It also leaves behind only what this slice cannot delete: no meeting
//   cancel/delete endpoint exists yet, so the created meeting intentionally
//   stays (a unique title plus a free future slot keep it harmless).
//
// RESIDUAL EXPOSURE (documented, not solved)
//   There is no delete-user / cancel-meeting endpoint in this project, so every
//   run permanently adds two accounts and one meeting to the staging database.
//   The accounts are now protected by a run-specific, env-supplied password
//   instead of a published one, which is the most this spec can do from here:
//   cleaning them up requires a backend API that does not exist yet. Until then
//   the mitigation is operational - treat the staging database as dirty, keep it
//   off the public internet, and rotate STAGING_E2E_PASSWORD if it leaks.
// =============================================================================

import { expect, test } from '@playwright/test'
import {
  API_BASE,
  DEV_API_BASE_URL,
  STAGING_PASSWORD,
  STAGING_TARGET,
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

// The staging host is provisioned separately; until one is configured the whole
// file is a no-op (and the `staging` project is not even created by the config).
test.skip(!STAGING_TARGET, 'staging base URL not configured (set STAGING_BASE_URL)')
// Defence in depth: `npm run test:e2e:staging` already refuses to start without
// it, so this only catches a bare `npx playwright test --project=staging`.
test.skip(
  !STAGING_PASSWORD,
  'STAGING_E2E_PASSWORD is not set: this spec creates real accounts on a shared staging database and must not reuse the published dev password (helpers.TEST_PASSWORD)'
)

test.describe('staging-smoke', () => {
  test('注册 -> UI 登录 -> 创建会议 -> 第二用户报名 -> 发起人通知已读', async ({
    page,
    browser
  }) => {
    // Two browser contexts + real-network latency + async MQ notification delivery.
    test.setTimeout(300_000)

    // Pre-flight: the browser target (staging, from the project's use.baseURL) and
    // the helper API base must be the same environment. A silent split - UI on
    // staging, setup on the dev API - is exactly the failure this asserts away,
    // and it must fire BEFORE the first write, never as a later mystery.
    expect(
      API_BASE,
      `API_BASE resolved to the local dev API while the browser targets ${STAGING_TARGET}`
    ).not.toBe(DEV_API_BASE_URL)

    const organizer = uniqName('stg_org')
    const joiner = uniqName('stg_join')
    const title = `STAGING冒烟会议 ${uniqName('m')}`

    // ---- 1) 两个全新用户：API 注册（UI 注册已由 auth.spec 覆盖；API 准备数据更稳定）----
    // Password comes from STAGING_E2E_PASSWORD, never from the published dev default.
    const orgTokens = await apiRegister(organizer, STAGING_PASSWORD)
    await apiRegister(joiner, STAGING_PASSWORD)

    // ---- 2) 发起人：走真实登录 UI（证明 staging 上的认证界面 + 后端可用）----
    await uiLogin(page, organizer, STAGING_PASSWORD)

    // ---- 3) 发起人 UI 创建会议 ----
    // 房间与空闲槽位由真实接口探测：staging 是共享库，不能假设任何槽位为空。
    const room = await pickRoom(orgTokens.accessToken)
    const slot = await findFreeSlot(orgTokens.accessToken, room.id, 30)

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
        {
          timeout: 60_000
        }
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

    // ---- 4) 第二用户：独立 context UI 登录 -> 报名 ----
    // 直接访问“复制分享链接”指向的同一个 URL；剪贴板授权与本冒烟链路无关，故不涉及。
    // `browser.newContext()` does NOT inherit the project's `use` options, so the
    // baseURL (and the locale/timezone the UI is asserted under) are passed
    // explicitly; without baseURL every relative goto() in uiLogin would throw.
    const joinerCtx = await browser.newContext({
      baseURL: STAGING_TARGET,
      locale: 'zh-CN',
      timezoneId: 'Asia/Shanghai'
    })
    const joinerPage = await joinerCtx.newPage()
    await uiLogin(joinerPage, joiner, STAGING_PASSWORD)
    await joinerPage.goto(`/meetings/${meetingId}`)
    await expect(joinerPage.locator('h2', { hasText: title })).toBeVisible()
    const joinRespPromise = joinerPage.waitForResponse(
      (r) =>
        r.url().includes(`/api/meetings/${meetingId}/participants`) &&
        r.request().method() === 'POST',
      { timeout: 60_000 }
    )
    await joinerPage.getByRole('button', { name: JOIN_BTN }).click()
    const joinResp = await joinRespPromise
    expect(joinResp.status(), `join -> ${joinResp.status()}`).toBe(200)
    await expect(
      joinerPage.locator('.participant-table .el-table__row', { hasText: joiner })
    ).toBeVisible()
    await expect(joinerPage.locator('.participant-table .el-table__row')).toHaveCount(2)

    // ---- 5) 发起人收到报名通知（RabbitMQ 异步投递，轮询等待）并标记已读 ----
    // 先做 API 级到达断言：投递是异步的，瞬态 toast 不是稳定信号。
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

    // UI：发起人账号由本次运行新建，收件箱里只有这一条通知，
    // 因此即便 staging 是共享库也不会有分页/筛选歧义。
    await page.goto('/notifications')
    const card = page.locator('.notification-item', { hasText: title })
    await expect(card).toBeVisible()
    await expect(card).toContainText('报名了你的会议')
    await expect(card).toHaveClass(/unread/)

    const readRespPromise = page.waitForResponse(
      (r) => /\/api\/notifications\/\d+\/read/.test(r.url()) && r.request().method() === 'PATCH',
      { timeout: 60_000 }
    )
    await card.getByRole('button', { name: /标\s*记\s*已\s*读/ }).click()
    const readResp = await readRespPromise
    expect(readResp.status(), `mark read -> ${readResp.status()}`).toBe(200)
    await expect(card).not.toHaveClass(/unread/)

    await joinerCtx.close()
  })
})
