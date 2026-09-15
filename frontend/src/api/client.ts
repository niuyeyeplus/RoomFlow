// Axios 实例：Bearer 注入、Result<T> 解包、401 自动刷新并重试（并发去重）
import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult, AuthTokenVO, ErrorResultData, FieldError } from '@/types/api'
import { clearSession, getAccessToken, getRefreshToken, setTokens } from '@/utils/token'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 公开端点：不注入 Bearer，401 不触发刷新重试 */
    _skipAuth?: boolean
    /** 内部标记：该请求已因 401 重试过一次 */
    _retried?: boolean
  }
}

/** 业务错误：code 为契约错误码，fieldErrors 为 40001 字段级错误 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number
  readonly fieldErrors: FieldError[]

  constructor(code: number, message: string, httpStatus = 0, fieldErrors: FieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
    this.fieldErrors = fieldErrors
  }
}

// VITE_API_BASE_URL：axios 直连后端时的绝对地址（如 http://localhost:8080）；
// 留空则使用同源相对路径，开发环境经 vite proxy 转发（见 vite.config.ts VITE_API_PROXY_TARGET）
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

// ---- 会话失效处理（可被测试替换，避免直接依赖 router） ----
export type UnauthorizedHandler = () => void

const defaultUnauthorizedHandler: UnauthorizedHandler = () => {
  const current = window.location.pathname + window.location.search
  const target = current.startsWith('/login')
    ? '/login'
    : `/login?redirect=${encodeURIComponent(current)}`
  window.location.assign(target)
}

let unauthorizedHandler: UnauthorizedHandler = defaultUnauthorizedHandler

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler ?? defaultUnauthorizedHandler
}

function handleSessionExpired(): void {
  clearSession()
  unauthorizedHandler()
}

// ---- 刷新去重：并发 401 共享同一次刷新 ----
let refreshing: Promise<string | null> | null = null

function refreshTokens(): Promise<string | null> {
  if (!refreshing) {
    const refreshToken = getRefreshToken()
    // 无 refresh token 时直接返回，不缓存 null —— 否则重新登录后锁残留，
    // 后续 401 永远跳过刷新端点被强制登出
    if (!refreshToken) return Promise.resolve(null)
    const pending = apiClient
      .request<unknown, AuthTokenVO>({
        method: 'POST',
        url: '/api/auth/refresh',
        data: { refreshToken },
        _skipAuth: true,
        _retried: true
      })
      .then((tokens) => {
        setTokens(tokens)
        return tokens.accessToken
      })
      .catch((e: unknown) => {
        // 仅刷新凭证失效（40104）视为会话终结；其余错误向上传播，
        // 由原请求以 ApiError 失败返回，不清会话
        if (e instanceof ApiError && e.code === 40104) return null
        throw e
      })
    refreshing = pending
    // 不论成败都在结算后释放去重锁；两个分支均不抛错避免未处理拒绝
    pending.then(resetRefreshing, resetRefreshing)
  }
  return refreshing
}

function resetRefreshing(): void {
  refreshing = null
}

// ---- 请求拦截：注入 Bearer ----
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (!config._skipAuth) {
    const token = getAccessToken()
    if (token) config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

interface ErrorBody {
  code?: number
  message?: string
  data?: ErrorResultData | null
}

// ---- 响应拦截：Result<T> 解包 + 401 刷新重试 + 错误规范化 ----
apiClient.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    if (body && typeof body.code === 'number') {
      if (body.code === 0) return body.data
      return Promise.reject(
        new ApiError(
          body.code,
          body.message || '操作失败',
          response.status,
          (body.data as ErrorResultData | null)?.fieldErrors ?? []
        )
      )
    }
    return response.data
  },
  async (error: AxiosError<ErrorBody>) => {
    const config = error.config as InternalAxiosRequestConfig | undefined
    const status = error.response?.status ?? 0
    const body = error.response?.data

    // 401：尝试用 Refresh Token 刷新并重放原请求（仅一次）
    if (status === 401 && config && !config._retried && !config._skipAuth) {
      config._retried = true
      let shouldReplay = false
      try {
        const newToken = await refreshTokens()
        if (newToken) {
          shouldReplay = true
        } else {
          // 无 refresh token 或刷新凭证失效（40104）：会话终结
          handleSessionExpired()
        }
      } catch {
        // 刷新通道本身失败（网络错误/5xx 等）：不清会话，原请求按 401 返回
      }
      // 重放移出 try：重放请求的拒绝不应被上面的 catch 吞掉
      if (shouldReplay) {
        return apiClient.request(config)
      }
    } else if (status === 401 && config?._retried && !config._skipAuth) {
      // 重放后仍 401：新凭证同样被拒绝，视为会话失效
      handleSessionExpired()
    }

    const code = typeof body?.code === 'number' ? body.code : status === 0 ? -1 : 50000
    const message = body?.message || (status === 0 ? '网络异常，请检查网络连接' : '服务器内部错误')
    return Promise.reject(new ApiError(code, message, status, body?.data?.fieldErrors ?? []))
  }
)

/** 统一请求入口：响应已被拦截器解包为 Result.data */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return apiClient.request(config) as unknown as Promise<T>
}
