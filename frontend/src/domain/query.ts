import type { AnswerCitation, RetrievalMode } from './types'
export const buildAnswerRequest = (values: {
  query: string
  mode: RetrievalMode
  topK: number
}) => ({ query: values.query.trim(), mode: values.mode, topK: values.topK })
export function citationLocation(citation: AnswerCitation) {
  const segments = [...citation.headingPath]
  const page = citation.pageNumber ?? citation.sourcePosition?.pageNumber
  if (page) segments.push(`第 ${page} 页`)
  else if (citation.sourcePosition?.startLine)
    segments.push(`第 ${citation.sourcePosition.startLine} 行`)
  return segments.join(' / ') || '文档正文'
}

export const answerStatusLabel = (status: string) =>
  ({ ANSWERED: '已回答', INSUFFICIENT_EVIDENCE: '资料不足' })[status] ?? status

export function answerFailurePolicy(code: string) {
  return {
    retryable: code !== 'ANSWER_MODEL_REQUEST_INVALID',
    clearPreview: ['ANSWER_OUTPUT_INVALID', 'ANSWER_MODEL_REQUEST_INVALID'].includes(code),
    restart: ['IDEMPOTENCY_CONFLICT', 'IDEMPOTENCY_CONTEXT_CHANGED'].includes(code),
  }
}

export interface QuerySnapshot {
  applicationId?: number
  knowledgeBaseId?: number
  query: string
  mode: RetrievalMode
  topK: number
  traceId: string
  actorRef: string
  operation: string
}
/** Explicit allowlist: credentials must never become part of exchange history. */
export function querySnapshot(value: QuerySnapshot): QuerySnapshot {
  return {
    applicationId: value.applicationId,
    knowledgeBaseId: value.knowledgeBaseId,
    query: value.query.trim(),
    mode: value.mode,
    topK: value.topK,
    traceId: value.traceId,
    actorRef: value.actorRef,
    operation: value.operation,
  }
}
