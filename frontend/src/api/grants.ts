import type { ApplicationGrant, GrantPermission, PageResult } from '../domain/types'
import { request, pageUrl } from './transport'
export const grantsApi = {
  listGrantsByApplication: (tenantId: number, applicationId: number, page: number, size: number) =>
    request<PageResult<ApplicationGrant>>(
      pageUrl(`/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/grants`, page, size),
    ),
  listGrantsByKnowledgeBase: (
    tenantId: number,
    knowledgeBaseId: number,
    page: number,
    size: number,
  ) =>
    request<PageResult<ApplicationGrant>>(
      pageUrl(
        `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/grants`,
        page,
        size,
      ),
    ),
  upsertGrant: (
    tenantId: number,
    applicationId: number,
    knowledgeBaseId: number,
    permission: GrantPermission,
  ) =>
    request<ApplicationGrant>(
      `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/grants/${knowledgeBaseId}`,
      { method: 'PUT', body: JSON.stringify({ permission }) },
    ),
  revokeGrant: (tenantId: number, applicationId: number, knowledgeBaseId: number) =>
    request<void>(
      `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/grants/${knowledgeBaseId}`,
      { method: 'DELETE' },
    ),
}
