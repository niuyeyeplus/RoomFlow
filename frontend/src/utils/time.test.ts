import { describe, expect, it } from 'vitest'
import {
  beijingDayRange,
  beijingToday,
  formatDateTime,
  formatTimeOnly,
  isQuarterAligned,
  toBeijingIso
} from './time'

describe('time utils (北京时间 +08:00)', () => {
  it('toBeijingIso emits +08:00 offset regardless of local tz', () => {
    // UTC 2026-09-16 02:30 = 北京 2026-09-16 10:30
    const d = new Date('2026-09-16T02:30:00Z')
    expect(toBeijingIso(d)).toBe('2026-09-16T10:30:00+08:00')
  })

  it('formatDateTime renders Beijing wall time', () => {
    expect(formatDateTime('2026-09-16T10:00:00+08:00')).toBe('2026-09-16 10:00')
    // 跨时区输入也应按北京时间展示
    expect(formatDateTime('2026-09-16T02:00:00Z')).toBe('2026-09-16 10:00')
  })

  it('formatTimeOnly renders HH:mm', () => {
    expect(formatTimeOnly('2026-09-16T10:30:00+08:00')).toBe('10:30')
  })

  it('beijingDayRange covers [00:00, 24:00) of the Beijing calendar day', () => {
    const { start, end } = beijingDayRange('2026-09-16')
    expect(start.toISOString()).toBe('2026-09-15T16:00:00.000Z')
    expect(end.getTime() - start.getTime()).toBe(24 * 3600 * 1000)
  })

  it('isQuarterAligned validates 15min alignment', () => {
    expect(isQuarterAligned(new Date('2026-09-16T10:00:00+08:00'))).toBe(true)
    expect(isQuarterAligned(new Date('2026-09-16T10:15:00+08:00'))).toBe(true)
    expect(isQuarterAligned(new Date('2026-09-16T10:07:00+08:00'))).toBe(false)
    expect(isQuarterAligned(new Date('2026-09-16T10:00:30+08:00'))).toBe(false)
  })

  it('beijingToday returns YYYY-MM-DD', () => {
    expect(beijingToday()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})
