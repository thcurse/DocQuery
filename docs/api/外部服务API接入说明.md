# DocQuery 外部服务 API 接入说明

支持普通请求和 POST SSE 流式回答；本地验证范围见 [流式回答说明](../../deploy/streaming.md)。

## 接入边界

业务系统负责最终用户登录和业务权限；DocQuery 负责租户隔离、Application Credential、KnowledgeBase Grant、检索、受控单轮回答与查询审计。Credential 只能保存在业务后端，不能下发到浏览器或移动端。

## 资源准备

1. 租户管理员创建 Application。
2. 为 Application 创建 Credential，并在创建时安全保存完整值。
3. 创建 KnowledgeBase，进入详情页复制 `knowledgeBaseId`。
4. 为 Application 和 KnowledgeBase 建立 `READ` 或 `READ_WRITE` Grant。
5. 上传文档，等待至少一个版本进入 `READY` 并成为 `activeVersion`。

## 服务接口

- `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/retrieve`：返回排序后的 canonical 原文证据。
- `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/answer`：基于证据生成受控单轮回答并返回引用。
- `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/answer/stream`：请求体和鉴权相同，增加 `Accept: text/event-stream`，逐段返回临时答案，完成后返回完整结果和引用。

Answer 引用以 `documentName`、`headingPath` 和 PDF 物理 `pageNumber` 作为用户可理解的位置；`blockId` 与 canonical range 仅为兼容和审计保留，业务界面不应直接展示。

必需请求头：

- `Authorization: Bearer dq_app_<keyId>.<secret>`
- `Idempotency-Key: <opaque-key>`
- `Content-Type: application/json`

可选请求头：

- `X-DocQuery-Trace-Id`：业务链路追踪标识。
- `X-DocQuery-Actor-Ref`：业务系统中的用户或调用主体引用，不应包含秘密。

请求体：

```json
{
  "query": "差旅住宿报销标准是什么？",
  "mode": "HYBRID",
  "topK": 5
}
```

`mode` 可取 `HYBRID`、`KEYWORD`、`SEMANTIC`，`topK` 取值 `1`—`20`。成功响应头返回 `X-DocQuery-Request-Id`，应记录用于查询审计和排障。

## 幂等与失败处理

每个新业务请求生成新的 `Idempotency-Key`；同一请求因超时重试时复用原 Key。`401` 不应自动无限重试；`404` 应检查 KnowledgeBase、Application 和 Grant；`409 REQUEST_IN_PROGRESS` 可短暂退避后复用原 Key；`409 IDEMPOTENCY_CONFLICT` 或 `IDEMPOTENCY_CONTEXT_CHANGED` 应生成新 Key 明确发起新请求；`503` 必须按错误码处理：模型调用失败和超时可由用户主动重试；配置不兼容不应原样重试。前端不自动重试或自动切换模型。

流式客户端必须等待 `done` 才能采信结果和引用，`error` 或未收到终止事件的断线均不能按成功处理。停止时保留部分文字并标注“未完成，未经最终校验”；`clearPreview=true` 的错误必须清空临时文字。普通与流式接口共享幂等结果，已完成请求的流式重放只发送 `start` 和 `done(replayed=true)`。不自动重连、不按 `Last-Event-ID` 恢复，也不自动重试流式模型。完整事件结构见 [流式协议](../../deploy/streaming.md#接口与事件)。

后台“使用说明”页面提供可复制 curl/Java 示例，并可下载：

- `docquery-service-api.openapi.yaml`
- `docquery-service-api.postman_collection.json`


## 回答校验与重新发起

- `ANSWERED` 必须有非空回答及至少一个实际提供给模型的有效引用；部分未知引用被过滤，全部无效则失败，不自动补造引用。
- 只有合法的 `INSUFFICIENT_EVIDENCE`（answer=null、citations=[]）表示资料不足。格式或引用错误不再伪装成资料不足，也不写入成功缓存。
- `ANSWER_OUTPUT_INVALID`：区分“模型回答格式校验失败”“答案引用校验失败”“模型输出不完整”。普通接口 HTTP 503；流式接口终止 error，clearPreview=true。
- `ANSWER_MODEL_REQUEST_INVALID`：供应商拒绝请求参数或 SDK 不支持配置，先修正配置。流式 retryable=false、clearPreview=true；不要原样自动重试。
- 完成结果的“重新回答”和幂等冲突后的“重新发起”由用户主动操作并生成新 Key；失败“重试”保留原问题、检索参数与 Key，使用当前凭证。运行中返回 409，不另开并发生成。
- 模型 profile、模型、协议、供应商地址摘要、推理强度、最终输出模式、输出额度及策略版本进入请求指纹。配置变更不清空 Redis；原 Key 与新指纹不符时返回既有冲突。
- 流式连接建立后的失败审计记录 HTTP 200 和失败 outcome；连接成功不等于回答成功。

最终输出配置与评测见 [问答稳定性改进说明](../../deploy/answer-stability.md)。
