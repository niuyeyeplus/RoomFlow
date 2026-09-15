// Vitest 全局 setup：补齐 jsdom 缺失的浏览器 API（Element Plus 组件依赖）
import { vi } from 'vitest'

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (!globalThis.ResizeObserver) {
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
}

if (!globalThis.matchMedia) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  }))
}
