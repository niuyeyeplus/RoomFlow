import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import ConfirmDialog from './ConfirmDialog.vue'

describe('ConfirmDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  function mountDialog(props: Record<string, unknown> = {}) {
    return mount(ConfirmDialog, {
      attachTo: document.body,
      props: { modelValue: true, message: '确定退出报名吗？', ...props },
      global: { plugins: [ElementPlus] }
    })
  }

  it('renders message and emits confirm', async () => {
    const wrapper = mountDialog()
    await nextTick()
    await nextTick()
    const dialog = document.body.querySelector('.el-dialog')
    expect(dialog?.textContent).toContain('确定退出报名吗？')
    const buttons = Array.from(document.body.querySelectorAll('.el-dialog button'))
    const confirmBtn = buttons.find((b) => b.textContent?.includes('确认')) as HTMLElement
    confirmBtn.click()
    await nextTick()
    expect(wrapper.emitted('confirm')).toBeTruthy()
    wrapper.unmount()
  })

  it('emits cancel and update:modelValue on cancel click', async () => {
    const wrapper = mountDialog({ confirmText: '移出', type: 'danger' })
    await nextTick()
    await nextTick()
    const buttons = Array.from(document.body.querySelectorAll('.el-dialog button'))
    const cancelBtn = buttons.find((b) => b.textContent?.includes('取消')) as HTMLElement
    cancelBtn.click()
    await nextTick()
    expect(wrapper.emitted('cancel')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    wrapper.unmount()
  })
})
