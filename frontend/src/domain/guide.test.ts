import { readFileSync } from 'node:fs'
import { describe, it, expect } from 'vitest'
describe('静态 API 交付物', () => {
  it('OpenAPI 与 Postman 保留普通接口并提供流式接口', () => {
    const schema = readFileSync('public/docs/docquery-service-api.openapi.yaml', 'utf8')
    expect(schema).toContain('/{knowledgeBaseId}/retrieve:')
    expect(schema).toContain('/{knowledgeBaseId}/answer:')
    expect(schema).toContain('/{knowledgeBaseId}/answer/stream:')
    const collection = JSON.parse(
      readFileSync('public/docs/docquery-service-api.postman_collection.json', 'utf8'),
    )
    expect(collection.item).toHaveLength(3)
  })
})
