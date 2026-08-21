import { Navigate, Outlet, useLocation } from 'react-router'
import { Spin } from 'antd'
import { useAuth } from './AuthContext'

export function RequireAuth() {
  const { admin, loading } = useAuth()
  const location = useLocation()
  if (loading) return <div className="center-screen"><Spin size="large" /></div>
  if (!admin) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}
