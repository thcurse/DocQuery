import { useEffect, useRef, useState } from 'react'

interface RequestCallbacks<T> {
  onStart?: () => void
  onSuccess: (result: T) => void
  onError: (error: unknown) => void
  onSettled?: () => void
}

// Sensitive request arguments stay in the current call, never in QueryClient caches.
export function usePageRequest() {
  const [isPending, setIsPending] = useState(false)
  const mountedRef = useRef(false)
  const controllerRef = useRef<AbortController | undefined>(undefined)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      controllerRef.current?.abort()
      controllerRef.current = undefined
    }
  }, [])

  const run = async <T,>(request: (signal: AbortSignal) => Promise<T>, callbacks: RequestCallbacks<T>) => {
    // A ref also blocks a second submission before React has rendered the loading state.
    if (!mountedRef.current || controllerRef.current) return
    const controller = new AbortController()
    controllerRef.current = controller
    setIsPending(true)
    callbacks.onStart?.()
    try {
      const result = await request(controller.signal)
      if (!controller.signal.aborted) callbacks.onSuccess(result)
    } catch (error) {
      if (!controller.signal.aborted) callbacks.onError(error)
    } finally {
      if (controllerRef.current === controller) controllerRef.current = undefined
      if (!controller.signal.aborted) {
        setIsPending(false)
        callbacks.onSettled?.()
      }
    }
  }

  return { isPending, run }
}
