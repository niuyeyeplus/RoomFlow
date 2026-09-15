// API client 拦截器测试：Result 解包、401 刷新重试、刷新去重、会话失效处理
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ApiError, apiClient, request, setUnauthorizedHandler } from './client'
import { clearSession, getAccessToken, getRefreshToken, setTokens } from '@/utils/token'
import type { AuthTokenVO } from '@/types/api'

type Handler = (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>

let handler: Handler

/** 模拟真实 adapter 的 settle 语义：validateStatus 不通过时 reject AxiosError（携带 response） */
function respond(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown
): Promise<AxiosResponse> {
  const response: AxiosResponse = {
    data,
    status,
    statusText: String(status),
    headers: {},
    config
  }
  const validate = config.validateStatus ?? ((s: number) => s >= 200 && s < 300)
  if (validate(status)) return Promise.resolve(response)
  const message =
    typeof (data as { message?: unknown })?.message === 'string'
      ? (data as { message: string }).message
      : `Request failed with status code ${status}`
  return Promise.reject(new AxiosError(message, 'ERR_BAD_RESPONSE', config, null, response))
}

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

const NEW_TOKENS: AuthTokenVO = {
  tokenType: 'Bearer',
  accessToken: 'new-access',
  accessTokenExpiresIn: 1800,
  refreshToken: 'new-refresh',
  refreshTokenExpiresIn: 2592000
}

beforeEach(() => {
  clearSession()
  apiClient.defaults.adapter = (config) => handler(config)
})

afterEach(() => {
  setUnauthorizedHandler(null)
  clearSession()
})

describe('api client', () => {
  it('unwraps Result.data on success', async () => {
    handler = (config) => respond(config, 200, { code: 0, message: 'success', data: { id: 1 } })
    const data = await request<{ id: number }>({ url: '/api/auth/me' })
    expect(data.id).toBe(1)
  })

  it('attaches Bearer token when session exists', async () => {
    setTokens({
      tokenType: 'Bearer',
      accessToken: 'token-abc',
      accessTokenExpiresIn: 1800,
      refreshToken: 'rt',
      refreshTokenExpiresIn: 2592000
    })
    let seenAuth = ''
    handler = (config) => {
      seenAuth = String(config.headers.Authorization ?? '')
      return respond(config, 200, { code: 0, message: 'ok', data: null })
    }
    await request({ url: '/api/rooms' })
    expect(seenAuth).toBe('Bearer token-abc')
  })

  it('rejects ApiError with fieldErrors when code !== 0', async () => {
    handler = (config) =>
      respond(config, 200, {
        code: 40001,
        message: '参数校验失败',
        data: { fieldErrors: [{ field: 'title', message: '不能为空' }] }
      })
    const err = await request({ url: '/api/meetings', method: 'POST' }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe(40001)
    expect((err as ApiError).fieldErrors[0].field).toBe('title')
  })

  it('rejects ApiError on HTTP error status with body code', async () => {
    handler = (config) => respond(config, 404, { code: 40401, message: '资源不存在', data: null })
    const err = await request({ url: '/api/meetings/999' }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe(40401)
    expect((err as ApiError).httpStatus).toBe(404)
  })

  it('refreshes token on 401 and retries original request once', async () => {
    setTokens({
      tokenType: 'Bearer',
      accessToken: 'expired-access',
      accessTokenExpiresIn: 1800,
      refreshToken: 'valid-refresh',
      refreshTokenExpiresIn: 2592000
    })
    const auths: string[] = []
    let refreshCalls = 0
    handler = (config) => {
      if (config.url === '/api/auth/refresh') {
        refreshCalls += 1
        return respond(config, 200, { code: 0, message: 'success', data: NEW_TOKENS })
      }
      auths.push(String(config.headers.Authorization ?? ''))
      if (auths.length === 1) {
        return respond(config, 401, { code: 40102, message: 'Token 过期', data: null })
      }
      return respond(config, 200, { code: 0, message: 'ok', data: [1, 2] })
    }

    const data = await request<number[]>({ url: '/api/rooms' })

    expect(refreshCalls).toBe(1)
    expect(auths).toEqual(['Bearer expired-access', 'Bearer new-access'])
    expect(data).toEqual([1, 2])
    expect(getAccessToken()).toBe('new-access')
    expect(getRefreshToken()).toBe('new-refresh')
  })

  it('dedupes concurrent 401 refreshes into a single refresh call', async () => {
    setTokens({
      tokenType: 'Bearer',
      accessToken: 'expired-access',
      accessTokenExpiresIn: 1800,
      refreshToken: 'valid-refresh',
      refreshTokenExpiresIn: 2592000
    })
    let refreshCalls = 0
    let refreshConfig: InternalAxiosRequestConfig | null = null
    const gate = deferred<AxiosResponse>()
    handler = (config) => {
      if (config.url === '/api/auth/refresh') {
        refreshCalls += 1
        refreshConfig = config
        return gate.promise
      }
      if (config.headers.Authorization === 'Bearer expired-access') {
        return respond(config, 401, { code: 40102, message: 'Token 过期', data: null })
      }
      return respond(config, 200, { code: 0, message: 'ok', data: null })
    }

    const p1 = request({ url: '/api/rooms' })
    const p2 = request({ url: '/api/meetings' })
    // 两个请求均已 401 并触发同一刷新
    await vi.waitFor(() => expect(refreshCalls).toBe(1))
    const cfg = refreshConfig as InternalAxiosRequestConfig | null
    gate.resolve({
      data: { code: 0, message: 'success', data: NEW_TOKENS },
      status: 200,
      statusText: '200',
      headers: {},
      config: cfg ?? ({} as InternalAxiosRequestConfig)
    })
    await Promise.all([p1, p2])
    expect(refreshCalls).toBe(1)
  })

  it('clears session and notifies when refresh fails', async () => {
    setTokens({
      tokenType: 'Bearer',
      accessToken: 'expired-access',
      accessTokenExpiresIn: 1800,
      refreshToken: 'bad-refresh',
      refreshTokenExpiresIn: 2592000
    })
    const onExpired = vi.fn()
    setUnauthorizedHandler(onExpired)
    handler = (config) => {
      if (config.url === '/api/auth/refresh') {
        return respond(config, 401, {
          code: 40104,
          message: 'Refresh Token 无效',
          data: null
        })
      }
      return respond(config, 401, { code: 40102, message: 'Token 过期', data: null })
    }

    const err = await request({ url: '/api/rooms' }).catch((e) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(onExpired).toHaveBeenCalledOnce()
  })

  it('clears session when the retried request still returns 401', async () => {
    setTokens({
      tokenType: 'Bearer',
      accessToken: 'expired-access',
      accessTokenExpiresIn: 1800,
      refreshToken: 'valid-refresh',
      refreshTokenExpiresIn: 2592000
    })
    const onExpired = vi.fn()
    setUnauthorizedHandler(onExpired)
    handler = (config) => {
      if (config.url === '/api/auth/refresh') {
        return respond(config, 200, { code: 0, message: 'success', data: NEW_TOKENS })
      }
      // 原请求与重放请求都返回 401
      return respond(config, 401, { code: 40102, message: 'Token 过期', data: null })
    }

    const err = await request({ url: '/api/rooms' }).catch((e) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(onExpired).toHaveBeenCalledOnce()
  })

  it('keeps session and returns ApiError when refresh fails with non-40104 error', async () => {
    setTokens({
      tokenType: 'Bearer',
      accessToken: 'expired-access',
      accessTokenExpiresIn: 1800,
      refreshToken: 'valid-refresh',
      refreshTokenExpiresIn: 2592000
    })
    const onExpired = vi.fn()
    setUnauthorizedHandler(onExpired)
    handler = (config) => {
      if (config.url === '/api/auth/refresh') {
        // 刷新通道 5xx：凭证未必失效，不应清会话
        return respond(config, 500, { code: 50000, message: '服务器内部错误', data: null })
      }
      return respond(config, 401, { code: 40102, message: 'Token 过期', data: null })
    }

    const err = await request({ url: '/api/rooms' }).catch((e) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe(40102)
    expect(getAccessToken()).toBe('expired-access')
    expect(onExpired).not.toHaveBeenCalled()
  })

  it('does not attempt refresh for _skipAuth endpoints (login failure)', async () => {
    let refreshCalls = 0
    handler = (config) => {
      if (config.url === '/api/auth/refresh') refreshCalls += 1
      return respond(config, 401, { code: 40103, message: '用户名或密码错误', data: null })
    }
    const err = await request({
      url: '/api/auth/login',
      method: 'POST',
      data: {},
      _skipAuth: true
    }).catch((e) => e)
    expect((err as ApiError).code).toBe(40103)
    expect(refreshCalls).toBe(0)
  })

  it('rejects network errors as ApiError code -1', async () => {
    handler = () => Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK'))
    const err = await request({ url: '/api/rooms' }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe(-1)
  })
})
