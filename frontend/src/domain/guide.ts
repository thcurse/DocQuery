export const retrieveCurl = `curl --request POST "{{baseUrl}}/api/v1/service/knowledge-bases/{{knowledgeBaseId}}/retrieve" \\
  --header "Authorization: Bearer {{credential}}" \\
  --header "Idempotency-Key: $(uuidgen)" \\
  --header "X-DocQuery-Trace-Id: order-service-demo" \\
  --header "X-DocQuery-Actor-Ref: user-10001" \\
  --header "Content-Type: application/json" \\
  --data '{
    "query": "差旅住宿报销标准是什么？",
    "mode": "HYBRID",
    "topK": 5
  }'`

export const answerCurl = `curl --request POST "{{baseUrl}}/api/v1/service/knowledge-bases/{{knowledgeBaseId}}/answer" \\
  --header "Authorization: Bearer {{credential}}" \\
  --header "Idempotency-Key: $(uuidgen)" \\
  --header "Content-Type: application/json" \\
  --data '{
    "query": "差旅住宿报销标准是什么？",
    "mode": "HYBRID",
    "topK": 5
  }'`

export const javaExample = `HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(baseUrl + "/api/v1/service/knowledge-bases/" + knowledgeBaseId + "/retrieve"))
    .header("Authorization", "Bearer " + credential)
    .header("Idempotency-Key", UUID.randomUUID().toString())
    .header("X-DocQuery-Trace-Id", businessTraceId)
    .header("X-DocQuery-Actor-Ref", businessUserId)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString("""
        {"query":"差旅住宿报销标准是什么？","mode":"HYBRID","topK":5}
        """))
    .build();

HttpResponse<String> response = httpClient.send(
    request, HttpResponse.BodyHandlers.ofString());`

export const errors = [
  {
    status: 400,
    code: 'INVALID_RETRIEVE_REQUEST / INVALID_ANSWER_REQUEST',
    handling: '修正 query、mode、topK 或必需请求头后，不复用错误请求参数。',
  },
  {
    status: 401,
    code: 'APPLICATION_CREDENTIAL_INVALID',
    handling: '检查 Credential 是否完整、是否已撤销；不要自动无限重试。',
  },
  {
    status: 404,
    code: 'KNOWLEDGE_BASE_NOT_AVAILABLE',
    handling: '检查 KB 状态、应用状态及 READ/READ_WRITE Grant。',
  },
  {
    status: 409,
    code: 'REQUEST_IN_PROGRESS',
    handling: '同一 Idempotency-Key 正在执行，短暂退避后用原 Key 查询同一请求。',
  },
  {
    status: 409,
    code: 'IDEMPOTENCY_CONFLICT',
    handling: '该 Key 已绑定其他请求，生成新 Key 后再提交新请求。',
  },
  {
    status: 409,
    code: 'IDEMPOTENCY_CONTEXT_CHANGED',
    handling: 'activeVersion 快照已变化，生成新 Key 明确发起新请求。',
  },
  {
    status: 503,
    code: 'QUERY_IDEMPOTENCY_UNAVAILABLE',
    handling: '幂等设施不可用，指数退避；不要绕过 Idempotency-Key。',
  },
  {
    status: 503,
    code: 'QUERY_EMBEDDING_UNAVAILABLE / SEARCH_UNAVAILABLE / EVIDENCE_UNAVAILABLE',
    handling: '依赖暂不可用，按业务超时预算有限重试。',
  },
  {
    status: 503,
    code: 'ANSWER_MODEL_UNAVAILABLE / ANSWER_EXECUTION_*',
    handling: '模型调用失败或超时，由用户主动重试，同一请求复用原 Key。',
  },
  {
    status: 503,
    code: 'ANSWER_OUTPUT_INVALID',
    handling: '回答格式、引用或完整性校验失败，不是资料不足；清除临时答案，用原 Key 主动重试。',
  },
  {
    status: 503,
    code: 'ANSWER_MODEL_REQUEST_INVALID',
    handling: '模型请求配置不兼容，先修正配置；不建议原样重试，配置变化后由用户重新发起新 Key。',
  },
]
