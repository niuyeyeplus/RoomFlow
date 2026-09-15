// 北京时间（固定 UTC+08:00）格式化与校验工具
// 契约约定：所有时间字段均为北京时间，ISO 8601 且显式携带 +08:00 偏移

const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** 将 Date 平移 +8h，使 UTC getter 读到的即北京时间 */
function asBeijing(d: Date): Date {
  return new Date(d.getTime() + BEIJING_OFFSET_MS)
}

function beijingParts(d: Date): { date: string; time: string } {
  const b = asBeijing(d)
  const date = `${b.getUTCFullYear()}-${pad2(b.getUTCMonth() + 1)}-${pad2(b.getUTCDate())}`
  const time = `${pad2(b.getUTCHours())}:${pad2(b.getUTCMinutes())}:${pad2(b.getUTCSeconds())}`
  return { date, time }
}

/** Date -> 'YYYY-MM-DDTHH:mm:ss+08:00'（提交给后端用） */
export function toBeijingIso(d: Date): string {
  const { date, time } = beijingParts(d)
  return `${date}T${time}+08:00`
}

/** ISO 时间 -> 'YYYY-MM-DD HH:mm'（北京时间展示） */
export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const { date, time } = beijingParts(d)
  return `${date} ${time.slice(0, 5)}`
}

/** ISO 时间 -> 'HH:mm'（北京时间展示） */
export function formatTimeOnly(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return beijingParts(d).time.slice(0, 5)
}

/** Date/ISO -> 北京时间日历日 'YYYY-MM-DD' */
export function toBeijingDate(value: Date | string): string {
  const d = typeof value === 'string' ? new Date(value) : value
  return beijingParts(d).date
}

/** 今天的北京时间日历日 'YYYY-MM-DD' */
export function beijingToday(): string {
  return toBeijingDate(new Date())
}

/** 北京时间日历日的 [00:00, 24:00) 区间 */
export function beijingDayRange(dateStr: string): { start: Date; end: Date } {
  const start = new Date(`${dateStr}T00:00:00+08:00`)
  return { start, end: new Date(start.getTime() + DAY_MS) }
}

/** 是否按 15 分钟对齐（秒=0、毫秒=0 且分钟 %15=0） */
export function isQuarterAligned(d: Date): boolean {
  return d.getSeconds() === 0 && d.getMilliseconds() === 0 && d.getMinutes() % 15 === 0
}

export const MIN_DURATION_MS = 15 * 60 * 1000
export const MAX_DURATION_MS = 24 * 60 * 60 * 1000
export const BOOKING_WINDOW_MS = 72 * 60 * 60 * 1000
