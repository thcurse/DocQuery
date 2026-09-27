import { effectScope } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { usePageRequest } from './usePageRequest'
describe('request cancellation', () => {
  it('stops immediately, allows another run and ignores late completion', async () => {
    const scope = effectScope()
    const request = scope.run(usePageRequest)!
    let resolveOld!: (value: string) => void
    const success = vi.fn(),
      cancelled = vi.fn()
    const old = request.run(
      () =>
        new Promise<string>((r) => {
          resolveOld = r
        }),
      { onSuccess: success, onError: vi.fn(), onCancel: cancelled },
    )
    request.cancel()
    expect(request.isPending.value).toBe(false)
    expect(cancelled).toHaveBeenCalledOnce()
    await request.run(async () => 'new', { onSuccess: success, onError: vi.fn() })
    resolveOld('old')
    await old
    expect(success).toHaveBeenCalledExactlyOnceWith('new')
    scope.stop()
  })
})
