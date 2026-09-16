// E2E helpers: real-API setup + UI flows. No mocks anywhere.
import { request, expect, type APIRequestContext, type Page } from '@playwright/test'

// ---------------------------------------------------------------------------
// Target resolution - mirrors playwright.config.ts (keep the two in step).
//
//   staging run (a staging URL is set)
//     API base: STAGING_API_BASE_URL -> the staging UI URL itself. The deployed
//              stack serves the SPA and /api/ from ONE origin (docker/nginx.conf
//              proxies /api/ to the backend), so a single staging URL is enough.
//              STAGING_API_BASE_URL is only for the case where the API is
//              reachable somewhere else from the runner.
//   dev/CI run (no staging URL)
//     API base: E2E_API_BASE_URL -> http://localhost:8080
//
// E2E_API_BASE_URL is deliberately NOT consulted once a staging URL is set. The
// two chains used to be ordered oppositely - the UI preferred STAGING_BASE_URL
// while the API preferred E2E_API_BASE_URL - so a developer with E2E_API_BASE_URL
// exported from local dev who then added STAGING_BASE_URL for a staging run would
// drive the browser against staging while every helper call (register / login /
// rooms / availability / notifications) went to the local dev API: either the
// staging login fails for a user that only exists on dev, or the setup passes
// against dev while the UI half is asserted against staging - a false green, plus
// junk rows in the dev database.
// ---------------------------------------------------------------------------
export const DEV_API_BASE_URL = 'http://localhost:8080'

/** The single staging switch, identical to `stagingURL` in playwright.config.ts. */
export const STAGING_TARGET = process.env.PLAYWRIGHT_BASE_URL ?? process.env.STAGING_BASE_URL

export const API_BASE = STAGING_TARGET
  ? (process.env.STAGING_API_BASE_URL ?? STAGING_TARGET)
  : (process.env.E2E_API_BASE_URL ?? DEV_API_BASE_URL)

// The other environment's API variable is ignored by the rule above; say so out
// loud instead of silently pointing at the wrong stack (a stray export is the
// exact mistake this resolution order exists to prevent).
if (STAGING_TARGET && process.env.E2E_API_BASE_URL) {
  console.warn(
    `[e2e] ignoring E2E_API_BASE_URL=${process.env.E2E_API_BASE_URL} because the staging target ` +
      `${STAGING_TARGET} is set; the API base is ${API_BASE}. Unset the staging variables to use it.`
  )
}
if (!STAGING_TARGET && process.env.STAGING_API_BASE_URL) {
  console.warn(
    `[e2e] ignoring STAGING_API_BASE_URL=${process.env.STAGING_API_BASE_URL} because no staging URL is set ` +
      `(STAGING_BASE_URL / PLAYWRIGHT_BASE_URL); the API base is ${API_BASE}. ` +
      `Use E2E_API_BASE_URL to point the dev suite at another API.`
  )
}

/**
 * Password for the local dev / CI suite. Unchanged, and deliberately still a
 * literal: the dev stack is a throwaway local database, and the existing specs
 * (auth.spec.ts) depend on this default.
 */
export const TEST_PASSWORD = 'E2e#Passw0rd'

/**
 * Password for the staging smoke spec. NEVER a literal, and never a fallback to
 * TEST_PASSWORD: staging is a shared, publicly reachable database and this
 * repository is public, so an account created with TEST_PASSWORD would be
 * login-capable by anyone who reads the repo. Empty when STAGING_E2E_PASSWORD is
 * unset - fail-closed. The staging spec skips on it, and `npm run
 * test:e2e:staging` refuses to start without it, so a staging account can never
 * be created with the well-known dev password.
 */
export const STAGING_PASSWORD = process.env.STAGING_E2E_PASSWORD ?? ''

interface Result<T> {
  code: number
  message: string
  data: T
}

export interface AuthTokens {
  tokenType: string
  accessToken: string
  accessTokenExpiresIn: number
  refreshToken: string
  refreshTokenExpiresIn: number
}

/** Unique, contract-legal username (3-50 chars, [A-Za-z0-9_]) to avoid dirty-data collisions. */
export function uniqName(prefix: string): string {
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
  return `${prefix}_${suffix}`.replace(/[^A-Za-z0-9_]/g, 'x').slice(0, 50)
}

async function api(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: API_BASE, timeout: 15_000 })
}

export interface ApiResp<T = unknown> {
  status: number
  code: number | null
  data: T | null
  raw: unknown
}

/** Raw API call through the real backend; returns status + unwrapped Result fields. */
export async function apiFetch<T = unknown>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  opts: { token?: string; data?: unknown; params?: Record<string, string | number | boolean> } = {}
): Promise<ApiResp<T>> {
  const ctx = await api()
  try {
    const res = await ctx.fetch(path, {
      method,
      data: opts.data,
      params: opts.params,
      headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : {}
    })
    const body = (await res.json().catch(() => null)) as Result<T> | null
    return { status: res.status(), code: body?.code ?? null, data: body?.data ?? null, raw: body }
  } finally {
    await ctx.dispose()
  }
}

export async function apiRegister(
  username: string,
  password = TEST_PASSWORD
): Promise<AuthTokens> {
  const ctx = await api()
  try {
    const res = await ctx.post('/api/auth/register', { data: { username, password } })
    const body = (await res.json()) as Result<AuthTokens>
    expect(res.status(), `register ${username}: ${JSON.stringify(body)}`).toBe(201)
    expect(body.code).toBe(0)
    return body.data
  } finally {
    await ctx.dispose()
  }
}

export async function apiLogin(username: string, password: string): Promise<AuthTokens> {
  const ctx = await api()
  try {
    const res = await ctx.post('/api/auth/login', { data: { username, password } })
    const body = (await res.json()) as Result<AuthTokens>
    expect(res.status(), `login ${username}: ${JSON.stringify(body)}`).toBe(200)
    expect(body.code).toBe(0)
    return body.data
  } finally {
    await ctx.dispose()
  }
}

// ---------- rooms / availability ----------

export interface Room {
  id: number
  name: string
  capacity: number
  enabled: boolean
}

export interface OccupiedSlot {
  startTime: string
  endTime: string
}

export interface Slot {
  start: Date
  end: Date
}

const QUARTER_MS = 15 * 60 * 1000

function beijingDateStr(d: Date): string {
  return new Date(d.getTime() + 8 * 3600 * 1000).toISOString().slice(0, 10)
}

/** First enabled room. */
export async function pickRoom(token: string): Promise<Room> {
  const r = await apiFetch<Room[]>('GET', '/api/rooms', { token, params: { enabled: true } })
  expect(r.status, `list rooms: ${JSON.stringify(r.raw)}`).toBe(200)
  const rooms = (r.data ?? []).filter((x) => x.enabled)
  expect(rooms.length, 'no enabled rooms seeded').toBeGreaterThan(0)
  return rooms[0]
}

/**
 * Find a 15-min-aligned free slot that starts in the future and ends within 24h,
 * using the real availability endpoint to avoid TIME_CONFLICT on re-runs.
 */
export async function findFreeSlot(token: string, roomId: number, durationMin = 30): Promise<Slot> {
  const now = Date.now()
  const first = Math.ceil((now + 10 * 60 * 1000) / QUARTER_MS) * QUARTER_MS
  const last = now + 24 * 3600 * 1000 - durationMin * 60 * 1000
  const r = await apiFetch<{ occupiedSlots: OccupiedSlot[] }>(
    'GET',
    `/api/rooms/${roomId}/availability`,
    {
      token,
      params: {
        startDate: beijingDateStr(new Date(now)),
        endDate: beijingDateStr(new Date(now + 48 * 3600 * 1000))
      }
    }
  )
  expect(r.status, `availability: ${JSON.stringify(r.raw)}`).toBe(200)
  const busy = (r.data?.occupiedSlots ?? []).map(
    (s) => [Date.parse(s.startTime), Date.parse(s.endTime)] as const
  )
  for (let t = first; t <= last; t += QUARTER_MS) {
    const e = t + durationMin * 60 * 1000
    if (!busy.some(([bs, be]) => t < be && e > bs)) {
      return { start: new Date(t), end: new Date(e) }
    }
  }
  throw new Error('no free 15-min-aligned slot within the next 24h')
}

/** Date -> 'YYYY-MM-DDTHH:mm:ss+08:00' (matches backend contract offset format). */
export function beijingIso(d: Date): string {
  return new Date(d.getTime() + 8 * 3600 * 1000).toISOString().slice(0, 19) + '+08:00'
}

/** 'YYYY-MM-DD HH:mm' in browser-local time (test env TZ = Asia/Shanghai). */
export function fmtPicker(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// ---------- UI flows ----------
// The dev stack goes through SSH-tunneled middleware and cold Vite transforms;
// a request occasionally aborts at the axios 15s timeout with no HTTP response.
// We therefore retry the gesture once *only when no response was observed*
// (nothing happened server-side) and otherwise assert on the real status.

async function clickAndAwaitResponse(
  page: Page,
  urlPart: string,
  click: () => Promise<void>,
  attempts = 2
): Promise<{ status: number; body: unknown } | null> {
  for (let i = 0; i < attempts; i++) {
    const respPromise = page
      .waitForResponse(
        (r) => r.url().includes(urlPart) && r.request().method() === 'POST',
        { timeout: 60_000 }
      )
      .catch(() => null)
    await click()
    const resp = await respPromise
    if (resp) {
      return { status: resp.status(), body: await resp.json().catch(() => null) }
    }
    // no response observed: the request was aborted client-side; safe to retry
  }
  return null
}

export async function uiLogin(page: Page, username: string, password = TEST_PASSWORD): Promise<void> {
  await page.goto('/login')
  await page.locator('input[placeholder="用户名"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(password)
  // Element Plus auto-inserts a space between two CJK chars in button text.
  const resp = await clickAndAwaitResponse(page, '/api/auth/login', () =>
    page.getByRole('button', { name: /登\s*录/ }).click()
  )
  expect(
    resp?.status,
    `POST /api/auth/login -> ${resp ? `${resp.status} ${JSON.stringify(resp.body)}` : 'no response'}`
  ).toBe(200)
  // generous budget: Vite dev cold-transform can take >15s on first hit
  await page.waitForURL('**/meetings', { timeout: 60_000 })
}

export async function uiRegister(
  page: Page,
  username: string,
  password = TEST_PASSWORD
): Promise<void> {
  await page.goto('/login')
  await page.getByRole('tab', { name: '注册' }).click()
  await page.locator('input[placeholder="用户名"]').fill(username)
  await page.locator('input[placeholder="密码"]').fill(password)
  await page.locator('input[placeholder="确认密码"]').fill(password)
  // NOTE: 单次点击、不重试 —— 注册非幂等，若首个请求在服务端已提交但响应丢失，
  // 重试只会得到 40901。无响应时先探测账号是否已创建再决定恢复路径。
  const resp = await clickAndAwaitResponse(
    page,
    '/api/auth/register',
    () => page.getByRole('button', { name: '注册并登录' }).click(),
    1
  )
  if (!resp) {
    // 客户端中止（axios 15s 超时），请求可能已提交服务端
    const probe = await apiFetch('POST', '/api/auth/login', {
      data: { username, password }
    })
    if (probe.status === 200) {
      // 注册实际已成功但响应丢失 —— 环境抖动；改用登录完成流程
      await uiLogin(page, username, password)
      return
    }
    // 确实未提交：安全重试一次
    const retry = await clickAndAwaitResponse(
      page,
      '/api/auth/register',
      () => page.getByRole('button', { name: '注册并登录' }).click(),
      1
    )
    if (retry?.status === 409) {
      // 首个请求在 probe 之后才提交 —— 账号已存在，改用登录恢复
      const probe2 = await apiFetch('POST', '/api/auth/login', {
        data: { username, password }
      })
      expect(probe2.status, 'register committed late; login should work').toBe(200)
      await uiLogin(page, username, password)
      return
    }
    expect(
      retry?.status,
      `register retry -> ${retry ? `${retry.status} ${JSON.stringify(retry.body)}` : 'no response'}`
    ).toBe(201)
    await page.waitForURL('**/meetings', { timeout: 60_000 })
    return
  }
  if (resp.status === 409) {
    // 极端竞态：注册已提交但早前某次尝试抢建成功 —— 用登录恢复
    const probe = await apiFetch('POST', '/api/auth/login', {
      data: { username, password }
    })
    expect(probe.status, 'register 409 but login failed').toBe(200)
    await uiLogin(page, username, password)
    return
  }
  expect(
    resp.status,
    `POST /api/auth/register -> ${resp.status} ${JSON.stringify(resp.body)}`
  ).toBe(201)
  await page.waitForURL('**/meetings', { timeout: 60_000 })
}

export async function uiLogout(page: Page): Promise<void> {
  for (let i = 0; i < 2; i++) {
    await page.locator('.header-right .username').click()
    await page.locator('.el-dropdown-menu__item:visible', { hasText: '退出登录' }).click()
    const box = page.locator('.el-message-box')
    const respPromise = page
      .waitForResponse(
        (r) => r.url().includes('/api/auth/logout') && r.request().method() === 'POST',
        { timeout: 60_000 }
      )
      .catch(() => null)
    await box.getByRole('button', { name: /退\s*出/ }).click()
    const resp = await respPromise
    if (resp) {
      if (resp.status() === 200) break
      if (resp.status() === 401 && i === 1) {
        // 首个请求实际已在服务端撤销会话（响应丢失）——校验 token 确已失效后收尾
        const me = await apiFetch('GET', '/api/auth/me', {
          token: (await page.evaluate(() => localStorage.getItem('roomflow.accessToken'))) ?? ''
        })
        expect(me.status, 'expected revoked token -> 401').toBe(401)
        await page.goto('/login')
        return
      }
      // NOTE: if the logout request itself fails, useAuth.logout propagates the
      // error and the router push never runs — surface the real API status so a
      // failure points at the backend, not an opaque navigation timeout.
      expect(
        resp.status(),
        `POST /api/auth/logout -> ${resp.status} ${await resp.text().catch(() => '')}`
      ).toBe(200)
    }
    // no response observed: request aborted before hitting the server; retry once
  }
  await page.waitForURL('**/login**', { timeout: 60_000 })
}

/**
 * Fill the two separate el-date-picker type="datetime" inputs inside the
 * create-meeting dialog (MeetingForm.vue: 开始时间 and 结束时间 are individual
 * pickers since the meeting-lifecycle UI split the old datetimerange editor).
 */
export async function fillTimeRange(page: Page, start: Date, end: Date): Promise<void> {
  const dialog = page.locator('.el-dialog:visible')
  const startInput = dialog.locator('input[placeholder="开始时间"]')
  const endInput = dialog.locator('input[placeholder="结束时间"]')
  await startInput.click()
  await startInput.fill(fmtPicker(start))
  // Enter commits the typed datetime and closes that picker's panel.
  await page.keyboard.press('Enter')
  await endInput.click()
  await endInput.fill(fmtPicker(end))
  // Enter applies the typed value and closes the picker panel. Do NOT press
  // Escape here: once the panel is closed it would propagate to el-dialog and
  // close the whole create-meeting dialog.
  await page.keyboard.press('Enter')
  const popper = page.locator('.el-picker__popper:visible')
  try {
    await popper.waitFor({ state: 'hidden', timeout: 3_000 })
  } catch {
    // Panel still open: click the dialog header to dismiss it without closing the dialog.
    await dialog.locator('.el-dialog__header').click()
    await popper.waitFor({ state: 'hidden', timeout: 3_000 }).catch(() => {})
  }
}
