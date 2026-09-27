import type {
  Application,
  ApplicationEnvironment,
  CreatedCredential,
  Credential,
  PageResult,
  StatusCode,
} from '../domain/types'
import { request, pageUrl } from './transport'
export const applicationsApi = {
  listApplications: (tenantId: number, page: number, size: number) =>
    request<PageResult<Application>>(
      pageUrl(`/api/admin/v1/tenants/${tenantId}/applications`, page, size),
    ),
  getApplication: (tenantId: number, applicationId: number) =>
    request<Application>(`/api/admin/v1/tenants/${tenantId}/applications/${applicationId}`),
  createApplication: (
    tenantId: number,
    body: {
      code: string
      name: string
      environment: ApplicationEnvironment
      description?: string
    },
  ) =>
    request<Application>(`/api/admin/v1/tenants/${tenantId}/applications`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateApplication: (
    tenantId: number,
    applicationId: number,
    body: {
      name?: string
      environment?: ApplicationEnvironment
      description?: string
      status?: StatusCode
    },
  ) =>
    request<Application>(`/api/admin/v1/tenants/${tenantId}/applications/${applicationId}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),

  listCredentials: (tenantId: number, applicationId: number, page: number, size: number) =>
    request<PageResult<Credential>>(
      pageUrl(
        `/api/admin/v1/tenants/${tenantId}/applications/${applicationId}/credentials`,
        page,
        size,
      ),
    ),
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
}
