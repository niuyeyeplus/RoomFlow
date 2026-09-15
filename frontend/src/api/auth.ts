// 认证 API（/api/auth/*）
import { request } from './client'
import type { AccountVO, AuthTokenVO, LoginRequest, RegisterRequest } from '@/types/api'

export function register(req: RegisterRequest): Promise<AuthTokenVO> {
  return request<AuthTokenVO>({
    method: 'POST',
    url: '/api/auth/register',
    data: req,
    _skipAuth: true
  })
}

export function login(req: LoginRequest): Promise<AuthTokenVO> {
  return request<AuthTokenVO>({
    method: 'POST',
    url: '/api/auth/login',
    data: req,
    _skipAuth: true
  })
}

export function logout(): Promise<null> {
  return request<null>({ method: 'POST', url: '/api/auth/logout' })
}

export function getCurrentAccount(): Promise<AccountVO> {
  return request<AccountVO>({ method: 'GET', url: '/api/auth/me' })
}
