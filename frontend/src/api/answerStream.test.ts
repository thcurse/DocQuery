import { afterEach, describe, expect, it, vi } from 'vitest'
import { answerStream, AnswerStreamError, SseDecoder } from './answerStream'
import { setUnauthorizedHandler } from './transport'
afterEach(() => vi.unstubAllGlobals())
const event = (name: string, data: unknown) =>
  'event: ' + name + '\r\ndata: ' + JSON.stringify(data) + '\r\n\r\n'
const final = { status: 'ANSWERED', answer: '中文😀', citations: [] }
function response(text: string) {
  const bytes = new TextEncoder().encode(text)
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
        controller.close()
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  )
}
const run = (callbacks = { progress: vi.fn(), delta: vi.fn() }) =>
  answerStream(
    1,
    'secret',
    'key',
    { query: 'q', mode: 'KEYWORD', topK: 5 },
    {},
    new AbortController().signal,
    callbacks,
  )
describe('SSE transport', () => {
  it('decodes UTF-8 split at every byte and resolves only after done', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          response(
            ': heartbeat\r\n\r\n' +
              event('start', { version: 1, requestId: 'request' }) +
              event('delta', { seq: 1, text: '中文😀' }) +
              event('done', { result: final }),
          ),
        ),
    )
    const callbacks = { progress: vi.fn(), delta: vi.fn() }
    expect((await run(callbacks)).data).toEqual(final)
    expect(callbacks.delta).toHaveBeenCalledWith('中文😀')
  })
  it('rejects missing terminal and missing sequence', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(response(event('start', { version: 1, requestId: 'r' }))),
    )
    await expect(run()).rejects.toMatchObject({ code: 'ANSWER_INTERRUPTED' })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          response(
            event('start', { version: 1, requestId: 'r' }) +
              event('delta', { seq: 2, text: 'bad' }),
          ),
        ),
    )
    await expect(run()).rejects.toBeInstanceOf(AnswerStreamError)
  })
  it('reports validation failure with clear-preview instruction', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response(
          event('error', {
            code: 'ANSWER_OUTPUT_INVALID',
            message: 'invalid',
            clearPreview: true,
            retryable: true,
          }),
        ),
      ),
    )
    await expect(run()).rejects.toMatchObject({ clearPreview: true })
  })
  it('does not log the administrator out on application 401', async () => {
    const unauthorized = vi.fn()
    setUnauthorizedHandler(unauthorized)
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('{"code":"APPLICATION_CREDENTIAL_INVALID"}', { status: 401 }),
        ),
    )
    await expect(run()).rejects.toMatchObject({ status: 401 })
    expect(unauthorized).not.toHaveBeenCalled()
    setUnauthorizedHandler(null)
  })
  it('supports multiline data and CR line separators', () => {
    const received: unknown[] = []
    const decoder = new SseDecoder((e) => received.push(e))
    for (const c of 'event: delta\rdata: first\rdata: second\r\r\n') decoder.feed(c)
    expect(received).toEqual([{ event: 'delta', data: 'first\nsecond' }])
  })
})
