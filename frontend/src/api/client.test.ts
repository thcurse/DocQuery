import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { __resetApiForTests, api, setUnauthorizedHandler } from './client'

const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' },
})

describe('admin api client security', () => {
  beforeEach(() => {
    __resetApiForTests()
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => vi.unstubAllGlobals())

  it.each(['retrieve', 'answer'] as const)('%s 在离页取消后终止包含凭证的请求', async (operation) => {
    const controller = new AbortController()
    vi.mocked(fetch).mockImplementation((_url, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('Request aborted', 'AbortError'))
      }, { once: true })
    }))

    const pending = api[operation](7, 'test-only-credential', 'query-key', {
      query: '问题', mode: 'HYBRID', topK: 5,
    }, undefined, controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()

    await rejected
    expect(vi.mocked(fetch).mock.calls[0][1]?.signal).toBe(controller.signal)
  })

  it('登录后丢弃旧 CSRF 并获取新 Session 的 Token', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'before-login' }))
      .mockResolvedValueOnce(jsonResponse({ id: 1, loginName: 'platform.admin', role: '1', tenantId: null }))
      .mockResolvedValueOnce(jsonResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'after-login' }))

    await api.login('platform.admin', 'LongEnoughPassword!')

    expect(fetchMock).toHaveBeenCalledTimes(3)
    const loginRequest = fetchMock.mock.calls[1][1] as RequestInit
    expect(new Headers(loginRequest.headers).get('X-CSRF-TOKEN')).toBe('before-login')
    expect(fetchMock.mock.calls[2][0]).toBe('/api/admin/v1/auth/csrf')
  })

  it('写请求使用内存中的 CSRF Header 且不重复拉取', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'memory-token' }))
      .mockResolvedValueOnce(jsonResponse({ tenant: { id: 3 }, initialAdmin: { loginName: 'tenant.admin' } }, 201))
      .mockResolvedValueOnce(jsonResponse({ id: 3, name: '新名称', status: '1' }))

    await api.createTenant({ tenantName: '租户', adminLoginName: 'tenant.admin', adminPassword: 'LongEnoughPassword!' })
    await api.updateTenant(3, { name: '新名称' })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    for (const index of [1, 2]) {
      const request = fetchMock.mock.calls[index][1] as RequestInit
      expect(new Headers(request.headers).get('X-CSRF-TOKEN')).toBe('memory-token')
      expect(request.credentials).toBe('same-origin')
    }
  })

  it('受保护请求返回 401 时清空前端身份', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ code: 'UNAUTHENTICATED', message: 'Authentication is required' }, 401))

    await expect(api.listTenants(0, 20)).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('租户管理员创建和密码重置只在写请求中提交密码', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'admin-token' }))
      .mockResolvedValueOnce(jsonResponse({
        id: 9,
        tenantId: 3,
        loginName: 'tenant.admin2',
        role: '2',
        status: '1',
      }, 201))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))

    await api.createTenantAdministrator(3, {
      loginName: 'tenant.admin2',
      password: 'LongEnoughPassword!',
    })
    await api.resetTenantAdministratorPassword(3, 9, 'AnotherLongPassword!')

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/v1/tenants/3/administrators')
    expect(JSON.parse((fetchMock.mock.calls[1][1] as RequestInit).body as string)).toEqual({
      loginName: 'tenant.admin2',
      password: 'LongEnoughPassword!',
    })
    expect(fetchMock.mock.calls[2][0]).toBe('/api/admin/v1/tenants/3/administrators/9/password')
    expect((fetchMock.mock.calls[2][1] as RequestInit).method).toBe('PUT')
    expect(JSON.parse((fetchMock.mock.calls[2][1] as RequestInit).body as string)).toEqual({
      password: 'AnotherLongPassword!',
    })
  })

  it('/auth/me 的匿名 401 不触发全局退出回调', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ code: 'UNAUTHENTICATED' }, 401))

    await expect(api.me()).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('服务 API 使用 Application Credential 且 401 不清除管理员 Session', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({
      code: 'APPLICATION_CREDENTIAL_INVALID',
      message: 'Application credential is invalid',
    }, 401))

    await expect(api.retrieve(7, 'dq_app_key.secret', 'query-key', {
      query: '差旅标准', mode: 'HYBRID', topK: 5,
    })).rejects.toMatchObject({ status: 401, code: 'APPLICATION_CREDENTIAL_INVALID' })

    const request = vi.mocked(fetch).mock.calls[0][1] as RequestInit
    const headers = new Headers(request.headers)
    expect(headers.get('Authorization')).toBe('Bearer dq_app_key.secret')
    expect(headers.get('Idempotency-Key')).toBe('query-key')
    expect(headers.has('X-CSRF-TOKEN')).toBe(false)
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('服务 API 返回 Request ID 且透传可选调用上下文', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify({
      queryExecutionId: 'execution-id',
      knowledgeBaseId: 7,
      requestedMode: 'KEYWORD',
      executedMode: 'KEYWORD',
      degraded: false,
      degradationReason: null,
      results: [],
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json', 'X-DocQuery-Request-Id': 'request-id' },
    }))

    const response = await api.retrieve(7, 'dq_app_key.secret', 'query-key', {
      query: '差旅标准', mode: 'KEYWORD', topK: 3,
    }, { traceId: 'trace-1', actorRef: 'user-1' })

    expect(response.requestId).toBe('request-id')
    const headers = new Headers((vi.mocked(fetch).mock.calls[0][1] as RequestInit).headers)
    expect(headers.get('X-DocQuery-Trace-Id')).toBe('trace-1')
    expect(headers.get('X-DocQuery-Actor-Ref')).toBe('user-1')
  })

  it('文档上传保留浏览器生成的 multipart boundary', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'upload-token' }))
      .mockResolvedValueOnce(jsonResponse({ documentId: 9, processingJobId: 11 }, 202))

    await api.uploadDocument(3, 7, '制度文档', new File(['body'], 'policy.md', { type: 'text/markdown' }), 'upload-key')

    const request = fetchMock.mock.calls[1][1] as RequestInit
    const headers = new Headers(request.headers)
    expect(request.body).toBeInstanceOf(FormData)
    const metadata = (request.body as FormData).get('metadata') as Blob
    expect(JSON.parse(await metadata.text())).toEqual({ documentName: '制度文档' })
    expect(headers.has('Content-Type')).toBe(false)
    expect(headers.get('X-CSRF-TOKEN')).toBe('upload-token')
    expect(headers.get('Idempotency-Key')).toBe('upload-key')
  })

  it('文档重建使用独立管理路由且不上传文件正文', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'rebuild-token' }))
      .mockResolvedValueOnce(jsonResponse({ documentId: 9, documentVersionId: 12, versionNo: 2 }, 202))

    await api.rebuildDocument(3, 7, 9, 'rebuild-key')

    expect(fetchMock.mock.calls[1][0]).toBe('/api/admin/v1/tenants/3/knowledge-bases/7/documents/9/rebuild')
    const request = fetchMock.mock.calls[1][1] as RequestInit
    const headers = new Headers(request.headers)
    expect(request.method).toBe('POST')
    expect(request.body).toBeUndefined()
    expect(headers.get('X-CSRF-TOKEN')).toBe('rebuild-token')
    expect(headers.get('Idempotency-Key')).toBe('rebuild-key')
  })
})
