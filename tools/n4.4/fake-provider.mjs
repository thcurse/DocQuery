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
  if (String(system).includes('controlled single-turn answer agent')) {
    return JSON.stringify({
      status: 'ANSWERED',
      answer: '普通员工出差住宿上限为每晚 500 元，超出部分需自行承担 [E1]',
      citedEvidenceIds: ['E1'],
    })
  }
  return JSON.stringify({ status: 'INSUFFICIENT_EVIDENCE', answer: null, citedEvidenceIds: [] })
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
      return send(response, 200, {
        id: `chatcmpl-${Date.now()}`,
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model ?? 'fake-chat',
        choices: [{
          index: 0,
          message: { role: 'assistant', content: chatContent(body) },
          finish_reason: 'stop',
        }],
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
