import type { Admin } from '../domain/types'
import { request, clearClientSecurityState, getCsrfToken } from './transport'
export const authApi = {
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
}
