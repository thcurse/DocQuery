import { answerStream } from '../api/answerStream'
/** Batch UI updates without retaining credentials or answers beyond the active call. */
export function useAnswerStream() {
  async function run(args: Parameters<typeof answerStream>): ReturnType<typeof answerStream> {
    const [kb, credential, key, body, context, signal, callbacks] = args
    let pending = ''
    const flush = () => {
      if (!signal.aborted && pending) callbacks.delta(pending)
      pending = ''
    }
    const timer = setInterval(flush, 50)
    try {
      const result = await answerStream(kb, credential, key, body, context, signal, {
        progress: callbacks.progress,
        delta: (text) => {
          pending += text
        },
      })
      flush()
      return result
    } catch (error) {
      flush()
      throw error
    } finally {
      clearInterval(timer)
      pending = ''
    }
  }
  return { run }
}
