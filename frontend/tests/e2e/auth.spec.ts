// 认证流程 E2E：注册 -> 自动登录 -> token 持久化 -> 退出 -> 服务端撤销 -> 重新登录
import { expect, test } from '@playwright/test'
import {
  apiFetch,
  apiLogin,
  apiRegister,
  uiLogin,
  uiLogout,
  uiRegister,
  uniqName,
  TEST_PASSWORD
} from './helpers'

test.describe('auth', () => {
  test('注册 -> 登录 -> 退出 -> 重新登录（含 token 持久化与撤销）', async ({ page }) => {
    const username = uniqName('e2e_auth')

    // 1) 注册并自动登录
    await uiRegister(page, username)
    await expect(page.locator('.header-right .username')).toHaveText(username)

    // 2) token 持久化：localStorage 存凭证，刷新页面后仍保持登录态
    const tokens = await page.evaluate(() => ({
      access: localStorage.getItem('roomflow.accessToken'),
      refresh: localStorage.getItem('roomflow.refreshToken')
    }))
    expect(tokens.access).toBeTruthy()
    expect(tokens.refresh).toBeTruthy()
    await page.reload()
    await expect(page).toHaveURL(/\/meetings/)
    await expect(page.locator('.header-right .username')).toHaveText(username)

    // 3) 退出登录：本地清理 + 服务端会话撤销
    await uiLogout(page)
    await expect(page).toHaveURL(/\/login/)
    const cleared = await page.evaluate(() => ({
      access: localStorage.getItem('roomflow.accessToken'),
      refresh: localStorage.getItem('roomflow.refreshToken')
    }))
    expect(cleared.access).toBeNull()
    expect(cleared.refresh).toBeNull()

    // 撤销验证：旧 access token 与旧 refresh token 均被服务端拒绝（Redis session revoke）
    const meAfterLogout = await apiFetch('GET', '/api/auth/me', { token: tokens.access! })
    expect(meAfterLogout.status, `me with revoked token: ${JSON.stringify(meAfterLogout.raw)}`).toBe(401)
    const staleRefresh = await apiFetch('POST', '/api/auth/refresh', {
      data: { refreshToken: tokens.refresh }
    })
    expect(staleRefresh.status).toBe(401)
    expect(staleRefresh.code).toBe(40104)

    // 4) 重新登录
    await uiLogin(page, username)
    await expect(page.locator('.header-right .username')).toHaveText(username)

    // 5) 30 天会话机制（不测时间本身）：refresh token 真实可换取新 token 对。
    //    注意：必须用独立的 API 会话验证——refresh 会旋转并立即作废旧会话，
    //    若用页面自己的 refresh token，页面持有的 access token 会被连带撤销。
    const apiSession = await apiLogin(username, TEST_PASSWORD)
    const refreshed = await apiFetch('POST', '/api/auth/refresh', {
      data: { refreshToken: apiSession.refreshToken }
    })
    expect(refreshed.status, `refresh: ${JSON.stringify(refreshed.raw)}`).toBe(200)
    expect(refreshed.code).toBe(0)
    // 旋转后旧 refresh token 立即失效（防重放）
    const replayRefresh = await apiFetch('POST', '/api/auth/refresh', {
      data: { refreshToken: apiSession.refreshToken }
    })
    expect(replayRefresh.status).toBe(401)
    expect(replayRefresh.code).toBe(40104)
    // 清理 API 侧会话（撤销旋转出的新会话）
    const rotated = refreshed.data as { accessToken?: string } | null
    if (rotated?.accessToken) {
      await apiFetch('POST', '/api/auth/logout', { token: rotated.accessToken })
    }

    // 清理：退出页面会话
    await uiLogout(page)
  })

  test('登录失败：错误密码给出错误提示且不跳转', async ({ page }) => {
    const username = uniqName('e2e_bad')
    await apiRegister(username) // UI 注册已由主用例覆盖，此处用 API 准备数据

    await page.goto('/login')
    await page.locator('input[placeholder="用户名"]').fill(username)
    await page.locator('input[placeholder="密码"]').fill('WrongPass!999')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await expect(page.locator('.el-message--error')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('未登录访问受保护路由被重定向到登录页', async ({ page }) => {
    await page.goto('/meetings')
    await expect(page).toHaveURL(/\/login\?redirect=/)
  })
})
