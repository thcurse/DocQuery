import { querySnapshot, answerStatusLabel, answerFailurePolicy } from './query'
import { describe, expect, it } from 'vitest'
import type { AnswerCitation } from './types'
import { buildAnswerRequest, citationLocation } from './query'

const citation = (overrides: Partial<AnswerCitation> = {}): AnswerCitation => ({
  citationIndex: 1,
  documentId: 2,
  documentVersionId: 3,
  versionNo: 1,
  documentName: '员工手册.pdf',
  headingNodeId: 'heading-1',
  headingPath: ['休假制度', '年假'],
  blockId: 'block-1',
  text: '员工可按规定申请年假。',
  truncated: false,
  canonicalStart: 10,
  canonicalEnd: 22,
  sourcePosition: null,
  pageNumber: 8,
  ...overrides,
})

describe('KnowledgeChatPage helpers', () => {
  it('只把当前问题和检索参数组成单轮 Answer 请求', () => {
    expect(buildAnswerRequest({ query: '  年假怎么申请？  ', mode: 'HYBRID', topK: 10 })).toEqual({
      query: '年假怎么申请？',
      mode: 'HYBRID',
      topK: 10,
    })
  })

  it('引用位置同时展示章节路径和页码', () => {
    expect(citationLocation(citation())).toBe('休假制度 / 年假 / 第 8 页')
  })

  it('没有章节和页码时明确标作文档正文', () => {
    expect(citationLocation(citation({ headingPath: [], pageNumber: null }))).toBe('文档正文')
  })
})

describe('回答失败与重新发起', () => {
  it('仅使用允许字段保存快照，清理凭证及空白', () => {
    const value = querySnapshot({
      query: '  问题 ',
      mode: 'HYBRID',
      topK: 5,
      traceId: '',
      actorRef: '',
      operation: 'answer',
      credential: 'secret',
    } as never)
    expect(value.query).toBe('问题')
    expect(value).not.toHaveProperty('credential')
  })
  it('区分输出失败、配置错误和幂等冲突', () => {
    expect(answerStatusLabel('ANSWERED')).toBe('已回答')
    expect(answerFailurePolicy('ANSWER_OUTPUT_INVALID')).toMatchObject({
      clearPreview: true,
      retryable: true,
    })
    expect(answerFailurePolicy('ANSWER_MODEL_REQUEST_INVALID')).toMatchObject({
      clearPreview: true,
      retryable: false,
    })
    expect(answerFailurePolicy('IDEMPOTENCY_CONFLICT').restart).toBe(true)
    expect(answerFailurePolicy('ANSWER_INTERRUPTED').clearPreview).toBe(false)
  })
})
