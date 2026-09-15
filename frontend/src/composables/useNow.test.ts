// useNow 响应式时钟测试：按间隔刷新、组件卸载后停止
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, type Ref } from 'vue'
import { useNow } from './useNow'

function mountClock(intervalMs = 1000): { now: Ref<number>; unmount: () => void } {
  let now!: Ref<number>
  const wrapper = mount(
    defineComponent({
      setup() {
        now = useNow(intervalMs)
        return () => h('div')
      }
    })
  )
  return { now, unmount: () => wrapper.unmount() }
}

describe('useNow', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns the current timestamp initially', () => {
    const { now, unmount } = mountClock()
    expect(now.value).toBe(Date.now())
    unmount()
  })

  it('refreshes the timestamp on each interval tick', () => {
    const { now, unmount } = mountClock(1000)
    const t0 = now.value
    vi.advanceTimersByTime(3500)
    // 3 次 tick（1s/2s/3s），末次取值 = t0 + 3000
    expect(now.value).toBe(t0 + 3000)
    unmount()
  })

  it('stops refreshing after the component unmounts', () => {
    const { now, unmount } = mountClock(1000)
    vi.advanceTimersByTime(1000)
    const t = now.value
    unmount()
    vi.advanceTimersByTime(5000)
    expect(now.value).toBe(t)
  })
})
