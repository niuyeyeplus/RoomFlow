import { describe, expect, it } from 'vitest'
import { friendlyMessage } from './errors'
import { ApiError } from '@/api/client'

describe('friendlyMessage', () => {
  it('maps contract error codes to Chinese hints', () => {
    expect(friendlyMessage(new ApiError(40902, 'conflict', 409))).toBe(
      '会议时间与该房间已有会议冲突'
    )
    expect(friendlyMessage(new ApiError(40904, 'banned', 409))).toBe('你已被该会议禁止报名')
    expect(friendlyMessage(new ApiError(40103, 'bad credentials', 401))).toBe('用户名或密码错误')
  })

  it('falls back to server message for unmapped codes', () => {
    expect(friendlyMessage(new ApiError(49999, '自定义错误', 400))).toBe('自定义错误')
  })

  it('handles plain errors and unknown values', () => {
    expect(friendlyMessage(new Error('boom'))).toBe('boom')
    expect(friendlyMessage(null)).toBe('操作失败，请稍后重试')
    expect(friendlyMessage(undefined)).toBe('操作失败，请稍后重试')
  })
})
