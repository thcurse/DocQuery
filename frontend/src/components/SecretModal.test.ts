import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import { NMessageProvider, NDialogProvider, NButton, NCheckbox } from 'naive-ui'
import SecretModal from './SecretModal.vue'
describe('一次性凭证', () => {
  it('要求保存确认后关闭，只有主动操作才复制', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h(NMessageProvider, null, {
            default: () =>
              h(NDialogProvider, null, { default: () => h(SecretModal, { secret: 'dq_secret' }) }),
          }),
      }),
      { attachTo: document.body },
    )
    const modal = wrapper.findComponent(SecretModal)
    const buttons = modal.findAllComponents(NButton)
    const done = buttons.find((b) => b.text() === '完成')!
    expect(done.props('disabled')).toBe(true)
    expect(writeText).not.toHaveBeenCalled()
    await buttons.find((b) => b.text() === '复制凭证')!.trigger('click')
    expect(writeText).toHaveBeenCalledWith('dq_secret')
    modal.findComponent(NCheckbox).vm.$emit('update:checked', true)
    await wrapper.vm.$nextTick()
    expect(done.props('disabled')).toBe(false)
    await done.trigger('click')
    expect(modal.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
