import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from 'antd'
import { describe, expect, it, vi } from 'vitest'
import { SecretModal } from './SecretModal'

describe('SecretModal', () => {
  it('要求管理员确认保存后才能关闭，并只通过回调清除', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
    })

    render(<App><SecretModal secret="dq_live_once_only" onClose={onClose} /></App>)

    const close = screen.getByRole('button', { name: /我已保存，关闭/ })
    expect(close).toBeDisabled()
    expect(screen.getByLabelText('完整应用凭证')).toHaveValue('dq_live_once_only')

    await user.click(screen.getByRole('checkbox'))
    expect(close).toBeEnabled()
    await user.click(close)
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('用户主动复制时才写入剪贴板', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    render(<App><SecretModal secret="dq_copy_once" onClose={() => undefined} /></App>)

    expect(writeText).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: /复制/ }))
    expect(writeText).toHaveBeenCalledWith('dq_copy_once')
  })
})
