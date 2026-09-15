// 空闲时段计算：契约只返回占用时段（ACTIVE），空闲区间 = 日期范围 − 占用时段，由前端推导
import type { TimeSlot } from '@/types/api'
import { beijingDayRange, toBeijingIso } from '@/utils/time'

export interface FreeSlot {
  /** 北京时间 ISO 8601（+08:00） */
  start: string
  end: string
}

/**
 * 计算某日（北京时间日历日 00:00-24:00）内的空闲时段。
 * 占用时段先裁剪到当日范围，合并重叠/相邻段后取补集。
 */
export function computeFreeSlots(dateStr: string, occupiedSlots: TimeSlot[]): FreeSlot[] {
  const { start: dayStart, end: dayEnd } = beijingDayRange(dateStr)
  const dayStartMs = dayStart.getTime()
  const dayEndMs = dayEnd.getTime()

  // 裁剪到当日并过滤无效段，再按 startTime 升序合并
  const clipped = occupiedSlots
    .map((s) => ({
      start: Math.max(new Date(s.startTime).getTime(), dayStartMs),
      end: Math.min(new Date(s.endTime).getTime(), dayEndMs)
    }))
    .filter((s) => s.start < s.end)
    .sort((a, b) => a.start - b.start)

  const merged: { start: number; end: number }[] = []
  for (const slot of clipped) {
    const last = merged[merged.length - 1]
    if (last && slot.start <= last.end) {
      last.end = Math.max(last.end, slot.end)
    } else {
      merged.push({ ...slot })
    }
  }

  const free: FreeSlot[] = []
  let cursor = dayStartMs
  for (const slot of merged) {
    if (slot.start > cursor) {
      free.push({ start: toBeijingIso(new Date(cursor)), end: toBeijingIso(new Date(slot.start)) })
    }
    cursor = Math.max(cursor, slot.end)
  }
  if (cursor < dayEndMs) {
    free.push({ start: toBeijingIso(new Date(cursor)), end: toBeijingIso(new Date(dayEndMs)) })
  }
  return free
}

export function useTimeSlot(): { computeFreeSlots: typeof computeFreeSlots } {
  return { computeFreeSlots }
}
