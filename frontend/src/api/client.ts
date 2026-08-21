import type {
  Admin,
  Application,
  ApplicationEnvironment,
  ApplicationGrant,
  CreatedCredential,
  CreatedTenant,
  Credential,
  DocumentDeletionAccepted,
  DocumentManagement,
  DocumentUploadAccepted,
  DocumentVersion,
  GrantPermission,
  KnowledgeBase,
  PageResult,
  ProcessingJob,
  ProcessingJobDetail,
  ProcessingRetryAccepted,
  QueryAudit,
  AnswerResponse,
  RetrieveResponse,
  RetrievalMode,
  ServiceCallResult,
  StatusCode,
  Tenant,
  TenantAdministrator,
} from '../domain/types'

interface CsrfToken {
  headerName: string
  parameterName: string
  token: string
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

let csrfToken: CsrfToken | null = null
let unauthorizedHandler: (() => void) | null = null

const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

const readError = async (response: Response) => {
  try {
    const body = await response.json() as { code?: string; message?: string }
    return new ApiError(
      response.status,
      body.code ?? `HTTP_${response.status}`,
      body.message ?? response.statusText,
    )
  } catch {
    return new ApiError(response.status, `HTTP_${response.status}`, response.statusText)
  }
}

const rawJson = async <T>(path: string, init: RequestInit = {}): Promise<T> => {
  const response = await fetch(path, {
    credentials: 'same-origin',
    ...init,
  })
  if (!response.ok) throw await readError(response)
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const getCsrfToken = async (force = false) => {
  if (!csrfToken || force) {
    csrfToken = await rawJson<CsrfToken>('/api/admin/v1/auth/csrf')
  }
  return csrfToken
}

export const setUnauthorizedHandler = (handler: (() => void) | null) => {
  unauthorizedHandler = handler
}

export const clearClientSecurityState = () => {
  csrfToken = null
}

export const __resetApiForTests = () => {
  csrfToken = null
  unauthorizedHandler = null
}

const request = async <T>(
  path: string,
  init: RequestInit = {},
  options: { ignoreUnauthorized?: boolean } = {},
): Promise<T> => {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (unsafeMethods.has(method)) {
    const csrf = await getCsrfToken()
    headers.set(csrf.headerName, csrf.token)
  }
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  try {
    return await rawJson<T>(path, { ...init, method, headers })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && !options.ignoreUnauthorized) {
      clearClientSecurityState()
      unauthorizedHandler?.()
    }
    throw error
  }
}

const pageUrl = (path: string, page: number, size: number) =>
  `${path}?page=${page}&size=${size}`

const queryUrl = (path: string, params: Record<string, string | number | undefined>) => {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value))
  })
  const suffix = query.toString()
  return suffix ? `${path}?${suffix}` : path
}

const serviceRequest = async <T>(
  path: string,
  credential: string,
  idempotencyKey: string,
  body: { query: string; mode: RetrievalMode; topK: number },
  context: { traceId?: string; actorRef?: string } = {},
): Promise<ServiceCallResult<T>> => {
  const headers = new Headers({
    Authorization: `Bearer ${credential}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
  })
  if (context.traceId) headers.set('X-DocQuery-Trace-Id', context.traceId)
  if (context.actorRef) headers.set('X-DocQuery-Actor-Ref', context.actorRef)
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify(body),
  })
  if (!response.ok) throw await readError(response)
  return {
    data: await response.json() as T,
    requestId: response.headers.get('X-DocQuery-Request-Id'),
  }
}

export const collectAllPages = async <T>(
  loader: (page: number, size: number) => Promise<PageResult<T>>,
) => {
  const size = 100
  const first = await loader(0, size)
  const items = [...first.items]
  const pages = Math.ceil(first.total / size)
  for (let page = 1; page < pages; page += 1) {
    const next = await loader(page, size)
    items.push(...next.items)
  }
  return items
}

export const api = {
  me: () => request<Admin>('/api/admin/v1/auth/me', {}, { ignoreUnauthorized: true }),
  login: async (loginName: string, password: string) => {
    const admin = await request<Admin>(
      '/api/admin/v1/auth/login',
      { method: 'POST', body: JSON.stringify({ loginName, password }) },
      { ignoreUnauthorized: true },
    )
    clearClientSecurityState()
    await getCsrfToken(true)
    return admin
  },
  logout: async () => {
    try {
      await request<void>('/api/admin/v1/auth/logout', { method: 'POST' })
    } finally {
      clearClientSecurityState()
    }
  },

  listTenants: (page: number, size: number) =>
    request<PageResult<Tenant>>(pageUrl('/api/admin/v1/tenants', page, size)),
  getTenant: (tenantId: number) => request<Tenant>(`/api/admin/v1/tenants/${tenantId}`),
  createTenant: (body: { tenantName: string; adminLoginName: string; adminPassword: string }) =>
    request<CreatedTenant>('/api/admin/v1/tenants', { method: 'POST', body: JSON.stringify(body) }),
  updateTenant: (tenantId: number, body: { name?: string; status?: StatusCode }) =>
    request<Tenant>(`/api/admin/v1/tenants/${tenantId}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
  listTenantAdministrators: (tenantId: number, page: number, size: number) =>
    request<PageResult<TenantAdministrator>>(pageUrl(
      `/api/admin/v1/tenants/${tenantId}/administrators`, page, size,
    )),
  createTenantAdministrator: (tenantId: number, body: { loginName: string; password: string }) =>
    request<TenantAdministrator>(`/api/admin/v1/tenants/${tenantId}/administrators`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateTenantAdministrator: (
    tenantId: number,
    administratorId: number,
    body: { status: StatusCode },
  ) => request<TenantAdministrator>(
    `/api/admin/v1/tenants/${tenantId}/administrators/${administratorId}`,
    { method: 'PATCH', body: JSON.stringify(body) },
  ),
  resetTenantAdministratorPassword: (
    tenantId: number,
    administratorId: number,
    password: string,
  ) => request<void>(
    `/api/admin/v1/tenants/${tenantId}/administrators/${administratorId}/password`,
    { method: 'PUT', body: JSON.stringify({ password }) },
  ),

  listApplications: (tenantId: number, page: number, size: number) =>
    request<PageResult<Application>>(pageUrl(
      `/api/admin/v1/tenants/${tenantId}/applications`, page, size,
    )),
  getApplication: (tenantId: number, applicationId: number) =>
    request<Application>(`/api/admin/v1/tenants/${tenantId}/applications/${applicationId}`),
  createApplication: (tenantId: number, body: {
    code: string
    name: string
    environment: ApplicationEnvironment
    description?: string
  }) => request<Application>(`/api/admin/v1/tenants/${tenantId}/applications`, {
    method: 'POST',
    body: JSON.stringify(body),
  }),
  updateApplication: (tenantId: number, applicationId: number, body: {
    name?: string
    environment?: ApplicationEnvironment
    description?: string
    status?: StatusCode
  }) => request<Application>(
    `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}`,
    { method: 'PATCH', body: JSON.stringify(body) },
  ),

  listKnowledgeBases: (tenantId: number, page: number, size: number) =>
    request<PageResult<KnowledgeBase>>(pageUrl(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases`, page, size,
    )),
  getKnowledgeBase: (tenantId: number, knowledgeBaseId: number) =>
    request<KnowledgeBase>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}`,
    ),
  createKnowledgeBase: (tenantId: number, body: { name: string; description?: string }) =>
    request<KnowledgeBase>(`/api/admin/v1/tenants/${tenantId}/knowledge-bases`, {
      method: 'POST', body: JSON.stringify(body),
    }),
  updateKnowledgeBase: (tenantId: number, knowledgeBaseId: number, body: {
    name?: string
    description?: string
    status?: StatusCode
  }) => request<KnowledgeBase>(
    `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}`,
    { method: 'PATCH', body: JSON.stringify(body) },
  ),

  listCredentials: (tenantId: number, applicationId: number, page: number, size: number) =>
    request<PageResult<Credential>>(pageUrl(
      `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/credentials`, page, size,
    )),
  createCredential: (tenantId: number, applicationId: number, name: string) =>
    request<CreatedCredential>(
      `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/credentials`,
      { method: 'POST', body: JSON.stringify({ name }) },
    ),
  revokeCredential: (tenantId: number, applicationId: number, credentialId: number) =>
    request<void>(
      `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/credentials/${credentialId}`,
      { method: 'DELETE' },
    ),

  listGrantsByApplication: (
    tenantId: number, applicationId: number, page: number, size: number,
  ) => request<PageResult<ApplicationGrant>>(pageUrl(
    `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/grants`, page, size,
  )),
  listGrantsByKnowledgeBase: (
    tenantId: number, knowledgeBaseId: number, page: number, size: number,
  ) => request<PageResult<ApplicationGrant>>(pageUrl(
    `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/grants`, page, size,
  )),
  upsertGrant: (
    tenantId: number,
    applicationId: number,
    knowledgeBaseId: number,
    permission: GrantPermission,
  ) => request<ApplicationGrant>(
    `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/grants/${knowledgeBaseId}`,
    { method: 'PUT', body: JSON.stringify({ permission }) },
  ),
  revokeGrant: (tenantId: number, applicationId: number, knowledgeBaseId: number) =>
    request<void>(
      `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/grants/${knowledgeBaseId}`,
      { method: 'DELETE' },
    ),

  listDocuments: (
    tenantId: number,
    knowledgeBaseId: number,
    filters: { name?: string; documentStatus?: string; latestVersionStatus?: string; page: number; size: number },
  ) => request<PageResult<DocumentManagement>>(queryUrl(
    `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents`,
    filters,
  )),
  getDocument: (tenantId: number, knowledgeBaseId: number, documentId: number) =>
    request<DocumentManagement>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
    ),
  listDocumentVersions: (
    tenantId: number, knowledgeBaseId: number, documentId: number, page: number, size: number,
  ) => request<PageResult<DocumentVersion>>(pageUrl(
    `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/versions`,
    page,
    size,
  )),
  uploadDocument: (
    tenantId: number, knowledgeBaseId: number, name: string, file: File, idempotencyKey: string,
  ) => {
    const form = new FormData()
    form.append('metadata', new Blob([JSON.stringify({ documentName: name })], { type: 'application/json' }))
    form.append('file', file)
    return request<DocumentUploadAccepted>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: form },
    )
  },
  uploadDocumentVersion: (
    tenantId: number, knowledgeBaseId: number, documentId: number, file: File, idempotencyKey: string,
  ) => {
    const form = new FormData()
    form.append('file', file)
    return request<DocumentUploadAccepted>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/versions`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: form },
    )
  },
  deleteDocument: (
    tenantId: number, knowledgeBaseId: number, documentId: number, idempotencyKey: string,
  ) => request<DocumentDeletionAccepted | undefined>(
    `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
    { method: 'DELETE', headers: { 'Idempotency-Key': idempotencyKey } },
  ),
  retryDocumentDeletion: (
    tenantId: number, knowledgeBaseId: number, documentId: number, idempotencyKey: string,
  ) => request<DocumentDeletionAccepted>(
    `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/deletion/retry`,
    { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey } },
  ),

  listProcessingJobs: (
    tenantId: number,
    filters: { knowledgeBaseId?: number; documentId?: number; documentVersionId?: number; status?: string; from?: string; to?: string; page: number; size: number },
  ) => request<PageResult<ProcessingJob>>(queryUrl(
    `/api/admin/v1/tenants/${tenantId}/processing-jobs`, filters,
  )),
  getProcessingJob: (tenantId: number, processingJobId: number) =>
    request<ProcessingJobDetail>(
      `/api/admin/v1/tenants/${tenantId}/processing-jobs/${processingJobId}`,
    ),
  retryProcessingJob: (tenantId: number, processingJobId: number, idempotencyKey: string) =>
    request<ProcessingRetryAccepted>(
      `/api/admin/v1/tenants/${tenantId}/processing-jobs/${processingJobId}/retry`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey } },
    ),

  retrieve: (
    knowledgeBaseId: number,
    credential: string,
    idempotencyKey: string,
    body: { query: string; mode: RetrievalMode; topK: number },
    context?: { traceId?: string; actorRef?: string },
  ) => serviceRequest<RetrieveResponse>(
    `/api/v1/service/knowledge-bases/${knowledgeBaseId}/retrieve`, credential, idempotencyKey, body, context,
  ),
  answer: (
    knowledgeBaseId: number,
    credential: string,
    idempotencyKey: string,
    body: { query: string; mode: RetrievalMode; topK: number },
    context?: { traceId?: string; actorRef?: string },
  ) => serviceRequest<AnswerResponse>(
    `/api/v1/service/knowledge-bases/${knowledgeBaseId}/answer`, credential, idempotencyKey, body, context,
  ),

  listQueryAudits: (
    tenantId: number,
    filters: {
      from?: string
      to?: string
      applicationId?: number
      knowledgeBaseId?: number
      operation?: string
      outcome?: string
      requestId?: string
      queryExecutionId?: string
      traceId?: string
      page: number
      size: number
    },
  ) => request<PageResult<QueryAudit>>(queryUrl(
    `/api/admin/v1/tenants/${tenantId}/query-audits`, filters,
  )),
  getQueryAudit: (tenantId: number, auditId: number) =>
    request<QueryAudit>(`/api/admin/v1/tenants/${tenantId}/query-audits/${auditId}`),
}
