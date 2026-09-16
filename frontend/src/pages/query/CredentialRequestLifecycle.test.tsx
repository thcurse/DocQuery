import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiPlaygroundPage } from './ApiPlaygroundPage'
import { KnowledgeChatPage } from './KnowledgeChatPage'

vi.mock('../../hooks/useTenantId', () => ({ useTenantId: () => 1 }))

const credential = 'dq_app_lifecycle.secret'
const question = '年假怎么申请？'
const clients: QueryClient[] = []
const cases = [
  { name: '知识库问答', Page: KnowledgeChatPage, operation: 'answer', chat: true },
  { name: 'API 调试 retrieve', Page: ApiPlaygroundPage, operation: 'retrieve', chat: false },
  { name: 'API 调试 answer', Page: ApiPlaygroundPage, operation: 'answer', chat: false },
] as const

const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json', 'X-DocQuery-Request-Id': 'request-123' },
})

const successResponse = (operation: string) => jsonResponse({
  queryExecutionId: 'execution-123',
  knowledgeBaseId: 7,
  requestedMode: 'HYBRID',
  executedMode: 'HYBRID',
  degraded: false,
  degradationReason: null,
  ...(operation === 'answer'
    ? { status: 'ANSWERED', answer: '请在系统中提交年假申请。', citations: [] }
    : { results: [] }),
})

function renderPage(Page: typeof KnowledgeChatPage | typeof ApiPlaygroundPage) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  clients.push(client)
  client.setQueryData(['applications', 1, 'all'], [{ id: 11, name: '测试应用', code: 'test', status: '1' }])
  client.setQueryData(['knowledge-bases', 1, 'all'], [{ id: 7, name: '测试知识库', status: '1' }])
  client.setQueryData(['grants', 1, 'application', 11], [{ knowledgeBaseId: 7, permission: '1', status: '1' }])
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter><Page /></MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...view, client }
}

async function fillRequest(testCase: typeof cases[number]) {
  const user = userEvent.setup()
  if (!testCase.chat && testCase.operation === 'answer') await user.click(screen.getByText('/answer', { exact: true }))
  await user.click(screen.getByRole('combobox', { name: 'Application' }))
  await user.click(await screen.findByText('测试应用 · test', { selector: '.ant-select-item-option-content' }))
  await user.click(screen.getByRole('combobox', { name: 'KnowledgeBase' }))
  await user.click(await screen.findByText('测试知识库 · ID 7', { selector: '.ant-select-item-option-content' }))
  await user.type(screen.getByLabelText('Application Credential'), `  ${credential}  `)
  const queryInput = screen.getByPlaceholderText(testCase.chat
    ? '输入关于当前知识库的问题；Enter 发送，Shift + Enter 换行'
    : '例如：差旅住宿报销标准是什么？')
  await user.type(queryInput, `  ${question}  `)
  const submitButton = screen.getByRole('button', { name: testCase.chat ? /发送$/ : /发送真实请求/ })
  return { user, queryInput, submitButton, form: submitButton.closest('form')! }
}

function expectNoCachedCredential(client: QueryClient) {
  expect(client.getMutationCache().getAll()).toHaveLength(0)
  expect(JSON.stringify(client.getQueryCache().getAll().map((query) => query.state.data))).not.toContain(credential)
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
  Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
})

afterEach(() => {
  cleanup()
  clients.splice(0).forEach((client) => client.clear())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe.each(cases)('$name 的 Credential 生命周期', (testCase) => {
  it('成功请求不进入全局缓存，并在同一轮重复提交时只发送一次', async () => {
    let complete!: (response: Response) => void
    vi.mocked(fetch).mockReturnValueOnce(new Promise<Response>((resolve) => { complete = resolve }))
    const { client, unmount } = renderPage(testCase.Page)
    const { form, submitButton, queryInput } = await fillRequest(testCase)

    fireEvent.submit(form)
    fireEvent.submit(form)
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce())
    expect(submitButton).toHaveClass('ant-btn-loading')
    expectNoCachedCredential(client)
    const [path, request] = vi.mocked(fetch).mock.calls[0]
    expect(path).toBe(`/api/v1/service/knowledge-bases/7/${testCase.operation}`)
    expect(new Headers(request?.headers).get('Authorization')).toBe(`Bearer ${credential}`)
    expect(JSON.parse(request?.body as string)).toEqual({ query: question, mode: 'HYBRID', topK: 5 })

    await act(async () => { complete(successResponse(testCase.operation)) })
    await screen.findByText(testCase.operation === 'answer' ? '请在系统中提交年假申请。' : '未召回结果')
    expect(submitButton).not.toHaveClass('ant-btn-loading')
    expect(queryInput).toHaveValue(testCase.chat ? '' : `  ${question}  `)
    if (!testCase.chat) {
      const previousKey = new Headers(request?.headers).get('Idempotency-Key')!
      expect(screen.queryByDisplayValue(previousKey)).not.toBeInTheDocument()
    }
    expectNoCachedCredential(client)
    unmount()
    expectNoCachedCredential(client)
  })

  it('失败后保留问题并可重试，失败和成功均不缓存凭证', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse({ code: 'APPLICATION_CREDENTIAL_INVALID', message: '凭证无效' }, 401))
      .mockResolvedValueOnce(successResponse(testCase.operation))
    const { client, unmount } = renderPage(testCase.Page)
    const { user, submitButton, queryInput } = await fillRequest(testCase)
    await user.click(submitButton)

    await screen.findByText('请求失败 · HTTP 401')
    expect(screen.getByText('凭证无效')).toBeInTheDocument()
    expect(queryInput).toHaveValue(`  ${question}  `)
    expect(submitButton).not.toHaveClass('ant-btn-loading')
    expectNoCachedCredential(client)

    await user.click(submitButton)
    await screen.findByText(testCase.operation === 'answer' ? '请在系统中提交年假申请。' : '未召回结果')
    expect(fetch).toHaveBeenCalledTimes(2)
    if (!testCase.chat) {
      const keys = vi.mocked(fetch).mock.calls.map(([, request]) => new Headers(request?.headers).get('Idempotency-Key'))
      expect(keys[1]).toBe(keys[0])
      expect(screen.queryByText('凭证无效')).not.toBeInTheDocument()
    }
    unmount()
    expectNoCachedCredential(client)
  })

  it('离页取消未完成请求，重新进入页面时凭证和旧结果都为空', async () => {
    let complete!: (response: Response) => void
    // Resolve after abort as well, to verify that a late response cannot update the old form.
    vi.mocked(fetch).mockReturnValueOnce(new Promise<Response>((resolve) => { complete = resolve }))
    const { client, unmount } = renderPage(testCase.Page)
    const { user, submitButton } = await fillRequest(testCase)
    await user.click(submitButton)
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce())
    const signal = vi.mocked(fetch).mock.calls[0][1]?.signal
    expect(signal?.aborted).toBe(false)

    unmount()
    expect(signal?.aborted).toBe(true)
    expectNoCachedCredential(client)
    renderPage(testCase.Page)
    await act(async () => { complete(successResponse(testCase.operation)) })
    expect(screen.getByLabelText('Application Credential')).toHaveValue('')
    expect(screen.queryByText('请在系统中提交年假申请。')).not.toBeInTheDocument()
    expect(screen.queryByText('未召回结果')).not.toBeInTheDocument()
    expectNoCachedCredential(client)
  })
})
