import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage, useDialog } from 'naive-ui'
import { useAuth } from '../stores/auth'
import { queryClient } from '../queryClient'
import { ApiError } from '../api/client'
export const errorMessage = (e: unknown) =>
  e instanceof ApiError
    ? `${e.message}（${e.code}）`
    : e instanceof Error
      ? e.message
      : '操作失败，请重试'
export const validId = (id: number) => Number.isSafeInteger(id) && id > 0
export function useIds() {
  const route = useRoute()
  const auth = useAuth()
  return {
    tenantId: computed(() => auth.admin?.tenantId ?? Number(route.params.tenantId)),
    id: computed(() => Number(route.params.id)),
    kbId: computed(() => Number(route.params.kbId)),
  }
}
export function useAction() {
  const busy = ref(false)
  const message = useMessage()
  const dialog = useDialog()
  async function run(task: () => Promise<unknown>, success = '操作成功', done?: () => void) {
    if (busy.value) return
    busy.value = true
    try {
      await task()
      done?.()
      message.success(success)
      await queryClient.invalidateQueries()
    } catch (e) {
      message.error(errorMessage(e))
    } finally {
      busy.value = false
    }
  }
  function confirm(title: string, content: string, task: () => Promise<unknown>) {
    dialog.warning({
      title,
      content,
      positiveText: `确认${title.slice(0, 2)}`,
      negativeText: '取消',
      onPositiveClick: () => run(task),
    })
  }
  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      message.success('已复制')
    } catch {
      message.error('复制失败，请手动复制')
    }
  }
  return { busy, run, confirm, copy }
}
export function validatePassword(password: string) {
  if (password.length < 12 || new TextEncoder().encode(password).length > 72)
    throw new Error('密码至少 12 个字符，最多 72 UTF-8 字节')
}
export const environments = [
  { value: 'DEVELOPMENT', label: '开发' },
  { value: 'TESTING', label: '测试' },
  { value: 'PRODUCTION', label: '生产' },
]
export const permissions = [
  { value: '1', label: '只读（Retrieve / Answer）' },
  { value: '2', label: '只写' },
  { value: '3', label: '读写' },
]
