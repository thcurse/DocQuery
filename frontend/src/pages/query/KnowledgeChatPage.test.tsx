import { describe, expect, it } from 'vitest'
import type { AnswerCitation } from '../../domain/types'
import { buildAnswerRequest, citationLocation } from './KnowledgeChatPage'

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
