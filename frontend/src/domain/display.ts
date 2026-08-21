import type {
  AdminRole,
  ApplicationEnvironment,
  DocumentStatus,
  DocumentVersionStatus,
  GrantPermission,
  JobStatus,
  StatusCode,
} from './types'

export const statusLabel = (status: StatusCode) => ({
  '1': '已启用',
  '2': '已停用',
  '3': '已撤销',
}[status] ?? `未知状态（${status}）`)

export const statusColor = (status: StatusCode) => ({
  '1': 'success',
  '2': 'default',
  '3': 'error',
}[status] ?? 'warning')

export const roleLabel = (role: AdminRole) => ({
  '1': '平台管理员',
  '2': '租户管理员',
}[role] ?? `未知角色（${role}）`)

export const environmentLabel = (environment: ApplicationEnvironment) => ({
  DEVELOPMENT: '开发',
  TESTING: '测试',
  PRODUCTION: '生产',
}[environment])

export const permissionLabel = (permission: GrantPermission) => ({
  '1': '只读',
  '2': '只写',
  '3': '读写',
}[permission] ?? `未知权限（${permission}）`)

export const formatDateTime = (value: string | null | undefined) => {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export const documentStatusLabel = (status: DocumentStatus) => ({
  '1': '可用',
  '2': '删除中',
  '3': '已删除',
}[status] ?? `未知状态（${status}）`)

export const versionStatusLabel = (status: DocumentVersionStatus) => ({
  '1': '处理中',
  '2': '已就绪',
  '3': '失败',
}[status] ?? `未知状态（${status}）`)

export const jobStatusLabel = (status: JobStatus) => ({
  '1': '等待执行',
  '2': '执行中',
  '3': '成功',
  '4': '失败',
}[status] ?? `未知状态（${status}）`)

export const workflowStatusColor = (status: string) => ({
  '1': 'processing',
  '2': 'processing',
  '3': 'success',
  '4': 'error',
}[status] ?? 'default')

export const versionStatusColor = (status: DocumentVersionStatus) => ({
  '1': 'processing',
  '2': 'success',
  '3': 'error',
}[status] ?? 'default')

export const documentStatusColor = (status: DocumentStatus) => ({
  '1': 'success',
  '2': 'processing',
  '3': 'default',
}[status] ?? 'default')

export const sourceFormatLabel = (format: string) => ({
  '1': 'PDF',
  '2': 'Word',
  '3': '纯文本',
  '4': 'Markdown',
  PDF: 'PDF',
  DOCX: 'Word',
  TXT: '纯文本',
  MARKDOWN: 'Markdown',
  MD: 'Markdown',
}[format.toUpperCase()] ?? format)

export const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}
