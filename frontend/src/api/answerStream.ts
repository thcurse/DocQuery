import type { AnswerResponse, RetrievalMode, ServiceCallResult } from '../domain/types'
import { ApiError, readError, serviceHeaders } from './transport'

export class AnswerStreamError extends ApiError {
  constructor(
    code: string,
    message: string,
    public clearPreview = false,
    public retryable = true,
  ) {
    super(200, code, message)
  }
}
export type StreamEvent = { event: string; data: string }
/** Retains incomplete lines/events across chunks; supports LF, CRLF and CR. */
export class SseDecoder {
  private pending = ''
  private event = ''
  private data: string[] = []
  constructor(private emit: (event: StreamEvent) => void) {}
  finish() {
    if (this.pending.endsWith('\r')) this.feed('\n')
  }
  feed(text: string) {
    this.pending += text
    if (this.pending.length > 2 * 1024 * 1024) throw new Error('流式事件过大')
    for (;;) {
      const index = this.pending.search(/[\r\n]/)
      if (index < 0 || (this.pending[index] === '\r' && index === this.pending.length - 1)) return
      const line = this.pending.slice(0, index)
      const length = this.pending[index] === '\r' && this.pending[index + 1] === '\n' ? 2 : 1
      this.pending = this.pending.slice(index + length)
      if (!line) {
        if (this.data.length)
          this.emit({ event: this.event || 'message', data: this.data.join('\n') })
        this.event = ''
        this.data = []
      } else if (!line.startsWith(':')) {
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'event') this.event = value
        if (field === 'data') {
          this.data.push(value)
          if (this.data.reduce((size, item) => size + item.length, 0) > 2 * 1024 * 1024)
            throw new Error('流式事件过大')
        }
      }
    }
  }
}
export async function answerStream(
  knowledgeBaseId: number,
  credential: string,
  key: string,
  body: { query: string; mode: RetrievalMode; topK: number },
  context: { traceId?: string; actorRef?: string },
  signal: AbortSignal,
  callbacks: { progress: (stage: string) => void; delta: (text: string) => void },
): Promise<ServiceCallResult<AnswerResponse>> {
  const headers = serviceHeaders(credential, key, context)
  headers.set('Accept', 'text/event-stream')
  const response = await fetch(
    '/api/v1/service/knowledge-bases/' + knowledgeBaseId + '/answer/stream',
    {
      method: 'POST',
      credentials: 'same-origin',
      headers,
      body: JSON.stringify(body),
      signal,
    },
  )
  if (!response.ok) throw await readError(response)
  if (!response.headers.get('Content-Type')?.includes('text/event-stream') || !response.body)
    throw new AnswerStreamError('INVALID_STREAM', '服务没有返回有效的流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let result: AnswerResponse | undefined
  let requestId = response.headers.get('X-DocQuery-Request-Id')
  let started = false
  let seq = 0
  const parser = new SseDecoder(({ event, data }) => {
    if (result || signal.aborted) return
    const value = JSON.parse(data)
    if (event === 'start') {
      if (started || value.version !== 1 || typeof value.requestId !== 'string')
        throw new AnswerStreamError('INVALID_STREAM', '流式协议不匹配')
      started = true
      requestId = value.requestId
    } else if (event === 'progress') {
      if (!started || !['retrieving', 'generating', 'validating'].includes(value.stage))
        throw new AnswerStreamError('INVALID_STREAM', '无效的回答状态')
      callbacks.progress(value.stage)
    } else if (event === 'delta') {
      if (!started || value.seq !== seq + 1 || typeof value.text !== 'string')
        throw new AnswerStreamError('INVALID_STREAM', '回答片段不完整，请重试')
      seq = value.seq
      callbacks.delta(value.text)
    } else if (event === 'done') {
      if (
        !started ||
        !value.result ||
        !['ANSWERED', 'INSUFFICIENT_EVIDENCE'].includes(value.result.status) ||
        !Array.isArray(value.result.citations) ||
        !(value.result.answer === null || typeof value.result.answer === 'string')
      )
        throw new AnswerStreamError('INVALID_STREAM', '回答结果无效', true)
      result = value.result as AnswerResponse
    } else if (event === 'error') {
      throw new AnswerStreamError(
        value.code || 'STREAM_ERROR',
        value.message || '回答失败',
        value.clearPreview === true,
        value.retryable === true,
      )
    }
  })
  try {
    while (!result) {
      const chunk = await reader.read()
      signal.throwIfAborted()
      if (chunk.done) {
        parser.feed(decoder.decode())
        parser.finish()
        if (!result)
          throw new AnswerStreamError('ANSWER_INTERRUPTED', '连接已中断，部分回答未经最终校验')
        break
      }
      parser.feed(decoder.decode(chunk.value, { stream: true }))
    }
    return { data: result!, requestId }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
