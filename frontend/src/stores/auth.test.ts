import { beforeEach, describe, it, expect, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuth } from './auth'
import { api, ApiError } from '../api/client'
import { queryClient } from '../queryClient'
beforeEach(() => {
  setActivePinia(createPinia())
  queryClient.clear()
  vi.restoreAllMocks()
})
describe('会话与缓存', () => {
  it('只恢复一次身份，并发导航共享请求', async () => {
    const user = { id: 1, loginName: 'admin', role: '2' as const, tenantId: 2 }
    const me = vi.spyOn(api, 'me').mockResolvedValue(user)
    const auth = useAuth()
    await Promise.all([auth.restore(), auth.restore()])
    await auth.restore()
    expect(me).toHaveBeenCalledTimes(1)
    expect(auth.admin).toEqual(user)
  })
  it('401 清空身份和缓存，网络故障可再次恢复', async () => {
    const auth = useAuth()
    const me = vi
      .spyOn(api, 'me')
      .mockRejectedValueOnce(new Error('offline'))
      .mockRejectedValueOnce(new ApiError(401, 'UNAUTHENTICATED', 'expired'))
    await expect(auth.restore()).rejects.toThrow('offline')
    expect(auth.initialized).toBe(false)
    queryClient.setQueryData(['private'], { tenantId: 1 })
    await auth.restore()
    expect(me).toHaveBeenCalledTimes(2)
    expect(auth.admin).toBeNull()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })
  it('退出请求失败也清空本地身份和缓存', async () => {
    vi.spyOn(api, 'logout').mockRejectedValue(new Error('offline'))
    const auth = useAuth()
    auth.admin = { id: 1, loginName: 'admin', role: '1', tenantId: null }
    queryClient.setQueryData(['private'], 1)
    await expect(auth.logout()).rejects.toThrow()
    expect(auth.admin).toBeNull()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })
})
