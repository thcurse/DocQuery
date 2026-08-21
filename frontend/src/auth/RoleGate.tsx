import { Result } from 'antd'
import type { AdminRole } from '../domain/types'
import { useAuth } from './AuthContext'

export function RoleGate({ role, children }: { role: AdminRole; children: React.ReactNode }) {
  const { admin } = useAuth()
  if (admin?.role !== role) {
    return (
      <Result
        status="403"
        title="无权访问"
        subTitle="当前管理员角色不能访问这个管理模块。"
      />
    )
  }
  return children
}
