import http from 'node:http'

const port = Number(process.env.DOCQUERY_FAKE_PROVIDER_PORT)
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('DOCQUERY_FAKE_PROVIDER_PORT must be a valid port')
}

const vector = Array.from({ length: 2560 }, (_, index) => index === 0 ? 1 : 0)

const send = (response, status, body) => {
  const json = JSON.stringify(body)
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(json),
  })
  response.end(json)
}

const chatContent = (body) => {
  const messages = Array.isArray(body.messages) ? body.messages : []
  const system = messages.find((message) => message.role === 'system')?.content ?? ''
  const user = [...messages].reverse().find((message) => message.role === 'user')?.content ?? '{}'
  let input = {}
  try { input = JSON.parse(typeof user === 'string' ? user : '{}') } catch { }

  if (String(system).includes('retrieval navigation cards')) {
    return JSON.stringify({
      items: (Array.isArray(input.items) ? input.items : []).map((item) => ({
        requestId: item.requestId,
        summary: '差旅制度与住宿报销标准说明。',
        topics: ['差旅', '住宿', '报销'],
        aliases: ['出差制度'],
        answerableQuestions: ['差旅住宿报销标准是什么？'],
      })),
    })
  }
  if (String(system).includes('document-level retrieval profile')) {
    return JSON.stringify({
      purpose: '说明员工差旅申请、住宿标准与报销规则。',
      topics: ['差旅', '住宿', '报销'],
      aliases: ['出差制度'],
      answerableQuestions: ['差旅住宿报销标准是什么？'],
    })
  }
  if (Array.isArray(input.evidencePackages)) {
    const evidenceIds = input.evidencePackages.flatMap((item) => item.anchorEvidenceIds ?? [])
    return JSON.stringify(evidenceIds.length ? {
      status: 'ANSWERED',
      answer: '普通员工出差住宿上限为每晚 500 元，超出部分需自行承担 [E1]',
      evidenceIds,
    } : { status: 'INSUFFICIENT_EVIDENCE', answer: null, evidenceIds: [] })
  }
  throw new Error('Unsupported fake-provider prompt')
}

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    return send(response, 200, { status: 'ok' })
  }
  let raw = ''
  request.setEncoding('utf8')
  request.on('data', (chunk) => { raw += chunk })
  request.on('end', () => {
    let body
    try { body = JSON.parse(raw || '{}') } catch { return send(response, 400, { error: { message: 'invalid json' } }) }
    if (request.method === 'POST' && request.url?.endsWith('/embeddings')) {
      const inputs = Array.isArray(body.input) ? body.input : [body.input]
      return send(response, 200, {
        object: 'list',
        data: inputs.map((_, index) => ({ object: 'embedding', index, embedding: vector })),
        model: body.model ?? 'fake-embedding',
        usage: { prompt_tokens: inputs.length, total_tokens: inputs.length },
      })
    }
    if (request.method === 'POST' && request.url?.endsWith('/chat/completions')) {
      const id = `chatcmpl-${Date.now()}`
      const model = body.model ?? 'fake-chat'
      // Production Agent must hand off selected real evidence through submit_evidence.
      const handoff = body.tools?.some((tool) => tool.function?.name === 'submit_evidence')
      const message = handoff
        ? { role: 'assistant', content: null, tool_calls: [{
            id: 'call-evidence', type: 'function', function: {
              name: 'submit_evidence', arguments: JSON.stringify({ evidenceIds: ['E1'], readRefs: [] }),
            },
          }] }
        : { role: 'assistant', content: chatContent(body) }
      if (body.stream) {
        response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
        response.flushHeaders()
        const text = Array.from(message.content)
        let offset = 0
        const write = (delta, finish_reason = null) => response.write(`data: ${JSON.stringify({
          id, object: 'chat.completion.chunk', model,
          choices: [{ index: 0, delta, finish_reason }],
        })}\n\n`)
        const timer = setInterval(() => {
          if (offset < text.length) {
            write({ content: text.slice(offset, offset += 5).join('') })
          } else {
            clearInterval(timer)
            write({}, 'stop')
            response.end('data: [DONE]\n\n')
          }
        }, 60)
        response.on('close', () => clearInterval(timer))
        return
      }
      return send(response, 200, {
        id, object: 'chat.completion', created: Math.floor(Date.now() / 1000), model,
        choices: [{ index: 0, message, finish_reason: handoff ? 'tool_calls' : 'stop' }],
        usage: { prompt_tokens: 8, completion_tokens: 8, total_tokens: 16 },
      })
    }
    return send(response, 404, { error: { message: 'not found' } })
  })
})

server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`fake-provider-ready:${port}\n`)
})

const shutdown = () => server.close(() => process.exit(0))
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
