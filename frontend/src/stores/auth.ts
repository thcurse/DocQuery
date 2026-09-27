import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, ApiError, clearClientSecurityState } from '../api/client'
import type { Admin } from '../domain/types'
import { queryClient } from '../queryClient'
export const useAuth = defineStore('auth', () => {
  const admin = ref<Admin | null>(null)
  const initialized = ref(false)
  let restoring: Promise<void> | null = null
  function clear() {
    admin.value = null
    clearClientSecurityState()
    queryClient.clear()
  }
  async function restore() {
    if (initialized.value) return
    if (!restoring)
      restoring = (async () => {
        try {
          admin.value = await api.me()
          initialized.value = true
        } catch (error) {
          if (error instanceof ApiError && error.status === 401) {
            clear()
            initialized.value = true
          } else throw error
        } finally {
          restoring = null
        }
      })()
    return restoring
  }
  async function login(name: string, password: string) {
    queryClient.clear()
    admin.value = await api.login(name, password)
    initialized.value = true
  }
  async function logout() {
    try {
      await api.logout()
    } finally {
      clear()
    }
  }
  return { admin, initialized, clear, restore, login, logout }
})
