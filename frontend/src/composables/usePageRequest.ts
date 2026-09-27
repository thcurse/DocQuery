import { onScopeDispose, ref } from 'vue'
export function usePageRequest() {
  const isPending = ref(false)
  let active = true
  let controller: AbortController | undefined
  let onCancel: (() => void) | undefined
  function cancel() {
    const current = controller
    controller = undefined
    current?.abort()
    isPending.value = false
    if (active) onCancel?.()
    onCancel = undefined
  }
  onScopeDispose(() => {
    active = false
    cancel()
  })
  async function run<T>(
    request: (signal: AbortSignal) => Promise<T>,
    callbacks: {
      onStart?: () => void
      onSuccess: (result: T) => void
      onError: (error: unknown) => void
      onCancel?: () => void
      onSettled?: () => void
    },
  ) {
    if (!active || controller) return
    const current = new AbortController()
    controller = current
    onCancel = callbacks.onCancel
    isPending.value = true
    callbacks.onStart?.()
    try {
      const result = await request(current.signal)
      if (active && controller === current && !current.signal.aborted) callbacks.onSuccess(result)
    } catch (error) {
      if (active && controller === current && !current.signal.aborted) callbacks.onError(error)
    } finally {
      if (controller === current) {
        controller = undefined
        onCancel = undefined
        isPending.value = false
        if (active) callbacks.onSettled?.()
      }
    }
  }
  return { isPending, run, cancel }
}
