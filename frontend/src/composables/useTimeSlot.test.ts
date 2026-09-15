import { describe, expect, it } from 'vitest'
import { computeFreeSlots } from './useTimeSlot'
import type { TimeSlot } from '@/types/api'

const slot = (meetingId: number, startTime: string, endTime: string): TimeSlot => ({
  meetingId,
  startTime,
  endTime
})

describe('computeFreeSlots', () => {
  it('returns whole day when nothing occupied', () => {
    const free = computeFreeSlots('2026-09-16', [])
    expect(free).toEqual([{ start: '2026-09-16T00:00:00+08:00', end: '2026-09-17T00:00:00+08:00' }])
  })

  it('returns gaps between occupied slots', () => {
    const free = computeFreeSlots('2026-09-16', [
      slot(1, '2026-09-16T10:00:00+08:00', '2026-09-16T11:30:00+08:00'),
      slot(2, '2026-09-16T14:00:00+08:00', '2026-09-16T15:00:00+08:00')
    ])
    expect(free).toEqual([
      { start: '2026-09-16T00:00:00+08:00', end: '2026-09-16T10:00:00+08:00' },
      { start: '2026-09-16T11:30:00+08:00', end: '2026-09-16T14:00:00+08:00' },
      { start: '2026-09-16T15:00:00+08:00', end: '2026-09-17T00:00:00+08:00' }
    ])
  })

  it('merges adjacent occupied slots (半开区间首尾相接)', () => {
    const free = computeFreeSlots('2026-09-16', [
      slot(1, '2026-09-16T09:00:00+08:00', '2026-09-16T10:00:00+08:00'),
      slot(2, '2026-09-16T10:00:00+08:00', '2026-09-16T11:00:00+08:00')
    ])
    expect(free).toEqual([
      { start: '2026-09-16T00:00:00+08:00', end: '2026-09-16T09:00:00+08:00' },
      { start: '2026-09-16T11:00:00+08:00', end: '2026-09-17T00:00:00+08:00' }
    ])
  })

  it('clips cross-day occupied slots to the queried day', () => {
    const free = computeFreeSlots('2026-09-16', [
      slot(1, '2026-09-15T22:00:00+08:00', '2026-09-16T02:00:00+08:00'),
      slot(2, '2026-09-16T23:00:00+08:00', '2026-09-17T01:00:00+08:00')
    ])
    expect(free).toEqual([{ start: '2026-09-16T02:00:00+08:00', end: '2026-09-16T23:00:00+08:00' }])
  })

  it('returns empty when fully occupied', () => {
    const free = computeFreeSlots('2026-09-16', [
      slot(1, '2026-09-15T20:00:00+08:00', '2026-09-17T08:00:00+08:00')
    ])
    expect(free).toEqual([])
  })
})
