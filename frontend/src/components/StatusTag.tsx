import { Tag } from 'antd'
import { statusColor, statusLabel } from '../domain/display'
import type { StatusCode } from '../domain/types'

export function StatusTag({ status }: { status: StatusCode }) {
  return <Tag color={statusColor(status)}>{statusLabel(status)}</Tag>
}
