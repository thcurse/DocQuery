import type { PageResult, RetrievalMode, ServiceCallResult } from '../domain/types'
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

export const readError = async (response: Response) => {
  try {
    const body = (await response.json()) as { code?: string; message?: string }
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

export const request = async <T>(
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

export const pageUrl = (path: string, page: number, size: number) =>
  `${path}?page=${page}&size=${size}`

export const queryUrl = (path: string, params: Record<string, string | number | undefined>) => {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value))
  })
  const suffix = query.toString()
  return suffix ? `${path}?${suffix}` : path
}

export const serviceHeaders = (
  credential: string,
  idempotencyKey: string,
  context: { traceId?: string; actorRef?: string } = {},
) => {
  const headers = new Headers({
    Authorization: `Bearer ${credential}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
  })
  if (context.traceId) headers.set('X-DocQuery-Trace-Id', context.traceId)
  if (context.actorRef) headers.set('X-DocQuery-Actor-Ref', context.actorRef)
  return headers
}

export const serviceRequest = async <T>(
  path: string,
  credential: string,
  idempotencyKey: string,
  body: { query: string; mode: RetrievalMode; topK: number },
  context: { traceId?: string; actorRef?: string } = {},
  signal?: AbortSignal,
): Promise<ServiceCallResult<T>> => {
  const headers = serviceHeaders(credential, idempotencyKey, context)
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok) throw await readError(response)
  return {
    data: (await response.json()) as T,
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
