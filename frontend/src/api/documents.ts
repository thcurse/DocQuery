import type {
  DocumentDeletionAccepted,
  DocumentManagement,
  DocumentUploadAccepted,
  DocumentVersion,
  PageResult,
  ProcessingJob,
  ProcessingJobDetail,
  ProcessingRetryAccepted,
} from '../domain/types'
import { request, pageUrl, queryUrl } from './transport'
export const documentsApi = {
  listDocuments: (
    tenantId: number,
    knowledgeBaseId: number,
    filters: {
      name?: string
      documentStatus?: string
      latestVersionStatus?: string
      page: number
      size: number
    },
  ) =>
    request<PageResult<DocumentManagement>>(
      queryUrl(
        `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents`,
        filters,
      ),
    ),
  getDocument: (tenantId: number, knowledgeBaseId: number, documentId: number) =>
    request<DocumentManagement>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
    ),
  listDocumentVersions: (
    tenantId: number,
    knowledgeBaseId: number,
    documentId: number,
    page: number,
    size: number,
  ) =>
    request<PageResult<DocumentVersion>>(
      pageUrl(
        `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/versions`,
        page,
        size,
      ),
    ),
  uploadDocument: (
    tenantId: number,
    knowledgeBaseId: number,
    name: string,
    file: File,
    idempotencyKey: string,
  ) => {
    const form = new FormData()
    form.append(
      'metadata',
      new Blob([JSON.stringify({ documentName: name })], { type: 'application/json' }),
    )
    form.append('file', file)
    return request<DocumentUploadAccepted>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: form },
    )
  },
  uploadDocumentVersion: (
    tenantId: number,
    knowledgeBaseId: number,
    documentId: number,
    file: File,
    idempotencyKey: string,
  ) => {
    const form = new FormData()
    form.append('file', file)
    return request<DocumentUploadAccepted>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/versions`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: form },
    )
  },
  rebuildDocument: (
    tenantId: number,
    knowledgeBaseId: number,
    documentId: number,
    idempotencyKey: string,
  ) =>
    request<DocumentUploadAccepted>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/rebuild`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey } },
    ),
  deleteDocument: (
    tenantId: number,
    knowledgeBaseId: number,
    documentId: number,
    idempotencyKey: string,
  ) =>
    request<DocumentDeletionAccepted | undefined>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
      { method: 'DELETE', headers: { 'Idempotency-Key': idempotencyKey } },
    ),
  retryDocumentDeletion: (
    tenantId: number,
    knowledgeBaseId: number,
    documentId: number,
    idempotencyKey: string,
  ) =>
    request<DocumentDeletionAccepted>(
      `/api/admin/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/deletion/retry`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey } },
    ),

  listProcessingJobs: (
    tenantId: number,
    filters: {
      knowledgeBaseId?: number
      documentId?: number
      documentVersionId?: number
      status?: string
      from?: string
      to?: string
      page: number
      size: number
    },
  ) =>
    request<PageResult<ProcessingJob>>(
      queryUrl(`/api/admin/v1/tenants/${tenantId}/processing-jobs`, filters),
    ),
  getProcessingJob: (tenantId: number, processingJobId: number) =>
    request<ProcessingJobDetail>(
      `/api/admin/v1/tenants/${tenantId}/processing-jobs/${processingJobId}`,
    ),
  retryProcessingJob: (tenantId: number, processingJobId: number, idempotencyKey: string) =>
    request<ProcessingRetryAccepted>(
      `/api/admin/v1/tenants/${tenantId}/processing-jobs/${processingJobId}/retry`,
      { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey } },
    ),
}
