// 响应式时钟：按固定间隔刷新当前时间戳，
// 供“是否已开始/进行中”等时间判定驱动 UI 随时间自动更新
import { onMounted, onUnmounted, ref, type Ref } from 'vue'

/**
 * 返回每 intervalMs 刷新一次的当前时间戳 ref。
 * 计时器随组件挂载启动、卸载清理。
 * 注意：刷新存在 intervalMs 粒度延迟，提交门控等需要精确时刻判定的
 * 场景应另以 Date.now() 现算。
 */
export function useNow(intervalMs = 30_000): Ref<number> {
  const now = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | undefined

  onMounted(() => {
    timer = setInterval(() => {
      now.value = Date.now()
    }, intervalMs)
  })

  onUnmounted(() => {
    if (timer !== undefined) {
      clearInterval(timer)
      timer = undefined
    }
  })

  return now
}
