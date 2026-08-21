import { Tag } from 'antd'
import {
  documentStatusColor,
  documentStatusLabel,
  jobStatusLabel,
  versionStatusColor,
  versionStatusLabel,
  workflowStatusColor,
} from '../domain/display'
import type { DocumentStatus, DocumentVersionStatus, JobStatus } from '../domain/types'

export function DocumentStatusTag({ status }: { status: DocumentStatus }) {
  return <Tag color={documentStatusColor(status)}>{documentStatusLabel(status)}</Tag>
}

export function VersionStatusTag({ status }: { status: DocumentVersionStatus }) {
  return <Tag color={versionStatusColor(status)}>{versionStatusLabel(status)}</Tag>
}

export function JobStatusTag({ status }: { status: JobStatus }) {
  return <Tag color={workflowStatusColor(status)}>{jobStatusLabel(status)}</Tag>
}
