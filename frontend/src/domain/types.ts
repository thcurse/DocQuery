export type StatusCode = '1' | '2' | '3'
export type AdminRole = '1' | '2'
export type GrantPermission = '1' | '2' | '3'
export type ApplicationEnvironment = 'DEVELOPMENT' | 'TESTING' | 'PRODUCTION'
export type DocumentStatus = '1' | '2' | '3'
export type DocumentVersionStatus = '1' | '2' | '3'
export type JobStatus = '1' | '2' | '3' | '4'
export type RetrievalMode = 'HYBRID' | 'KEYWORD' | 'SEMANTIC'

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface Admin {
  id: number
  loginName: string
  role: AdminRole
  tenantId: number | null
}

export interface Tenant {
  id: number
  name: string
  status: StatusCode
  createdAt: string
  updatedAt: string
}

export interface TenantAdministrator {
  id: number
  tenantId: number
  loginName: string
  role: AdminRole
  status: StatusCode
  createdAt: string
  updatedAt: string
}

export interface Application {
  id: number
  tenantId: number
  code: string
  name: string
  environment: ApplicationEnvironment
  description: string | null
  status: StatusCode
  createdAt: string
  updatedAt: string
}

export interface KnowledgeBase {
  id: number
  tenantId: number
  name: string
  description: string | null
  status: StatusCode
  createdAt: string
  updatedAt: string
}

export interface Credential {
  id: number
  applicationId: number
  name: string
  keyIdPrefix: string
  status: StatusCode
  createdAt: string
  lastUsedAt: string | null
  revokedAt: string | null
}

export interface CreatedCredential extends Credential {
  credential: string
}

export interface ApplicationGrant {
  id: number
  tenantId: number
  applicationId: number
  knowledgeBaseId: number
  permission: GrantPermission
  status: StatusCode
  grantedAt: string
  grantedBy: number
  revokedAt: string | null
  revokedBy: number | null
}

export interface CreatedTenant {
  tenant: Tenant
  initialAdmin: {
    id: number
    loginName: string
    role: AdminRole
    status: StatusCode
    tenantId: number
  }
}

export interface DocumentVersionSummary {
  documentVersionId: number
  versionNo: number
  status: DocumentVersionStatus
  active: boolean
  failureCode: string | null
  failureMessage: string | null
  failureRetryable: boolean | null
  readyAt: string | null
  failedAt: string | null
}

export interface DeletionJob {
  deletionJobId: number
  attemptNo: number
  status: JobStatus
  failureCode: string | null
  failureMessage: string | null
  failureRetryable: boolean | null
  requestedByAdminId: number
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface DocumentManagement {
  documentId: number
  knowledgeBaseId: number
  name: string
  documentStatus: DocumentStatus
  activeVersion: DocumentVersionSummary | null
  latestVersion: DocumentVersionSummary | null
  deletionRequestedAt: string | null
  deletedAt: string | null
  latestDeletionJob: DeletionJob | null
  createdAt: string
  updatedAt: string
}

export interface ProcessingJobSummary {
  processingJobId: number
  attemptNo: number
  status: JobStatus
  failureCode: string | null
  failureMessage: string | null
  failureRetryable: boolean | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface DocumentVersion {
  documentVersionId: number
  versionNo: number
  status: DocumentVersionStatus
  active: boolean
  sourceFormat: string
  originalFilename: string
  sourceSizeBytes: number
  failureCode: string | null
  failureMessage: string | null
  failureRetryable: boolean | null
  readyAt: string | null
  failedAt: string | null
  contentDeletedAt: string | null
  createdAt: string
  updatedAt: string
  latestProcessingJob: ProcessingJobSummary | null
}

export interface DocumentUploadAccepted {
  documentId: number
  documentVersionId: number
  versionNo: number
  processingJobId: number
  documentStatus: DocumentStatus
  versionStatus: DocumentVersionStatus
  jobStatus: JobStatus
  acceptedAt: string
}

export interface DocumentDeletionAccepted {
  documentId: number
  deletionJobId: number
  attemptNo: number
  documentStatus: DocumentStatus
  jobStatus: JobStatus
  replayed: boolean
  acceptedAt: string
}

export interface ProcessingJob {
  processingJobId: number
  knowledgeBaseId: number
  knowledgeBaseName: string
  documentId: number
  documentName: string
  documentVersionId: number
  versionNo: number
  jobType: string
  attemptNo: number
  status: JobStatus
  failureCode: string | null
  failureMessage: string | null
  failureRetryable: boolean | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ProcessingJobDetail {
  job: ProcessingJob
  attemptHistory: ProcessingJobSummary[]
}

export interface ProcessingRetryAccepted {
  sourceProcessingJobId: number
  documentId: number
  documentVersionId: number
  processingJobId: number
  attemptNo: number
  versionStatus: DocumentVersionStatus
  jobStatus: JobStatus
  replayed: boolean
  acceptedAt: string
}

export interface SourcePosition {
  sourceType: string | null
  pageNumber: number | null
  pageBlockOrdinal: number | null
  pageCharacterStart: number | null
  pageCharacterEnd: number | null
  bodyElementIndex: number | null
  tableRow: number | null
  tableColumn: number | null
  cellParagraphIndex: number | null
  startLine: number | null
  startColumn: number | null
  endLine: number | null
  endColumn: number | null
}

export interface HighlightSegment {
  text: string
  matched: boolean
}

export interface HighlightFragment {
  segments: HighlightSegment[]
}

export interface RetrievalEvidence {
  blockId: string
  kind: string
  text: string
  truncated: boolean
  canonicalStart: number
  canonicalEnd: number
  sourcePosition: SourcePosition | null
  keywordHighlights: HighlightFragment[]
}

export interface RetrieveResult {
  rank: number
  documentId: number
  documentVersionId: number
  versionNo: number
  documentName: string
  headingNodeId: string | null
  headingPath: string[]
  channels: string[]
  keywordRank: number | null
  semanticRank: number | null
  evidence: RetrievalEvidence[]
}

export interface RetrieveResponse {
  queryExecutionId: string
  knowledgeBaseId: number
  requestedMode: RetrievalMode
  executedMode: RetrievalMode
  degraded: boolean
  degradationReason: string | null
  results: RetrieveResult[]
}

export interface AnswerCitation {
  evidenceId: string
  documentId: number
  documentVersionId: number
  versionNo: number
  documentName: string
  headingNodeId: string | null
  headingPath: string[]
  blockId: string
  text: string
  truncated: boolean
  canonicalStart: number
  canonicalEnd: number
  sourcePosition: SourcePosition | null
}

export interface AnswerResponse {
  queryExecutionId: string
  knowledgeBaseId: number
  status: string
  answer: string | null
  requestedMode: RetrievalMode
  executedMode: RetrievalMode
  degraded: boolean
  degradationReason: string | null
  citations: AnswerCitation[]
}

export interface ServiceCallResult<T> {
  data: T
  requestId: string | null
}

export interface QueryAudit {
  id: number
  requestId: string
  tenantId: number
  applicationId: number
  credentialId: number
  credentialFingerprint: string
  knowledgeBaseId: number
  operation: string
  callerTraceId: string | null
  actorRef: string | null
  querySha256: string
  queryCodePoints: number
  requestedMode: string
  executedMode: string | null
  outcome: string
  httpStatus: number
  failureCategory: string | null
  failureCode: string | null
  queryExecutionId: string | null
  idempotencyDisposition: string | null
  snapshotFingerprint: string | null
  activeVersionCount: number | null
  degraded: boolean | null
  degradationReason: string | null
  resultCount: number | null
  evidenceCount: number | null
  answerStatus: string | null
  citationCount: number | null
  toolRounds: number | null
  toolCalls: number | null
  modelCalls: number | null
  canonicalCharacters: number | null
  startedAt: string
  completedAt: string | null
  durationMs: number | null
}
