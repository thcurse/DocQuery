import { Navigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'

export function HomeRedirect() {
  const { admin } = useAuth()
  return <Navigate to={admin?.role === '1' ? '/tenants' : '/applications'} replace />
}
