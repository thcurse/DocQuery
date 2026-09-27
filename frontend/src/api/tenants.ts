import type {
  CreatedTenant,
  PageResult,
  StatusCode,
  Tenant,
  TenantAdministrator,
} from '../domain/types'
import { request, pageUrl } from './transport'
export const tenantsApi = {
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
    request<PageResult<TenantAdministrator>>(
      pageUrl(`/api/admin/v1/tenants/${tenantId}/administrators`, page, size),
    ),
  createTenantAdministrator: (tenantId: number, body: { loginName: string; password: string }) =>
    request<TenantAdministrator>(`/api/admin/v1/tenants/${tenantId}/administrators`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateTenantAdministrator: (
    tenantId: number,
    administratorId: number,
    body: { status: StatusCode },
  ) =>
    request<TenantAdministrator>(
      `/api/admin/v1/tenants/${tenantId}/administrators/${administratorId}`,
      { method: 'PATCH', body: JSON.stringify(body) },
    ),
  resetTenantAdministratorPassword: (tenantId: number, administratorId: number, password: string) =>
    request<void>(`/api/admin/v1/tenants/${tenantId}/administrators/${administratorId}/password`, {
      method: 'PUT',
      body: JSON.stringify({ password }),
    }),
}
