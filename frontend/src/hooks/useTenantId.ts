import { useAuth } from '../auth/AuthContext'

export const useTenantId = () => {
  const { admin } = useAuth()
  if (!admin?.tenantId) throw new Error('Tenant context is required')
  return admin.tenantId
}
