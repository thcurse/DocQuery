import type {
  PageResult,
  QueryAudit,
  AnswerResponse,
  RetrieveResponse,
  RetrievalMode,
} from '../domain/types'
import { request, queryUrl, serviceRequest } from './transport'
export const queriesApi = {
  retrieve: (
    knowledgeBaseId: number,
    credential: string,
    idempotencyKey: string,
    body: { query: string; mode: RetrievalMode; topK: number },
    context?: { traceId?: string; actorRef?: string },
    signal?: AbortSignal,
  ) =>
    serviceRequest<RetrieveResponse>(
      `/api/v1/service/knowledge-bases/${knowledgeBaseId}/retrieve`,
      credential,
      idempotencyKey,
      body,
      context,
      signal,
    ),
  answer: (
    knowledgeBaseId: number,
    credential: string,
    idempotencyKey: string,
    body: { query: string; mode: RetrievalMode; topK: number },
    context?: { traceId?: string; actorRef?: string },
    signal?: AbortSignal,
  ) =>
    serviceRequest<AnswerResponse>(
      `/api/v1/service/knowledge-bases/${knowledgeBaseId}/answer`,
      credential,
      idempotencyKey,
      body,
      context,
      signal,
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
  ) =>
    request<PageResult<QueryAudit>>(
      queryUrl(`/api/admin/v1/tenants/${tenantId}/query-audits`, filters),
    ),
  getQueryAudit: (tenantId: number, auditId: number) =>
    request<QueryAudit>(`/api/admin/v1/tenants/${tenantId}/query-audits/${auditId}`),
}
