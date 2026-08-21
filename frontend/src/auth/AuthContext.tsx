import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { ApiError, api, clearClientSecurityState, setUnauthorizedHandler } from '../api/client'
import type { Admin } from '../domain/types'
import { queryClient } from '../queryClient'

interface AuthContextValue {
  admin: Admin | null
  loading: boolean
  login: (loginName: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [admin, setAdmin] = useState<Admin | null>(null)
  const [loading, setLoading] = useState(true)

  const clearSession = useCallback(() => {
    setAdmin(null)
    clearClientSecurityState()
    queryClient.clear()
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(clearSession)
    api.me()
      .then(setAdmin)
      .catch((error: unknown) => {
        if (!(error instanceof ApiError && error.status === 401)) throw error
        setAdmin(null)
      })
      .catch(() => setAdmin(null))
      .finally(() => setLoading(false))
    return () => setUnauthorizedHandler(null)
  }, [clearSession])

  const login = useCallback(async (loginName: string, password: string) => {
    const authenticated = await api.login(loginName, password)
    queryClient.clear()
    setAdmin(authenticated)
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } finally {
      clearSession()
    }
  }, [clearSession])

  const value = useMemo(() => ({ admin, loading, login, logout }), [admin, loading, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export const useAuth = () => {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
