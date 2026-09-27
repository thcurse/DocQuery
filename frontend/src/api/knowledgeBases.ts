import type { KnowledgeBase, PageResult, StatusCode } from '../domain/types'
import { request, pageUrl } from './transport'
export const knowledgeBasesApi = {
  listKnowledgeBases: (tenantId: number, page: number, size: number) =>
    request<PageResult<KnowledgeBase>>(
      pageUrl(`/api/admin/v1/tenants/${tenantId}/knowledge-bases`, page, size),
    ),
  getKnowledgeBase: (tenantId: number, knowledgeBaseId: number) =>
    request<KnowledgeBase>(`/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}`),
  createKnowledgeBase: (tenantId: number, body: { name: string; description?: string }) =>
    request<KnowledgeBase>(`/api/admin/v1/tenants/${tenantId}/knowledge-bases`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateKnowledgeBase: (
    tenantId: number,
    knowledgeBaseId: number,
    body: {
      name?: string
      description?: string
      status?: StatusCode
    },
  ) =>
    request<KnowledgeBase>(`/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
}
