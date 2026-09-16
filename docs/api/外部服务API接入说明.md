# DocQuery 外部服务 API 接入说明

状态：N4.4 已实现并验证，等待用户验收。

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

每个新业务请求生成新的 `Idempotency-Key`；同一请求因超时重试时复用原 Key。`401` 不应自动无限重试；`404` 应检查 KnowledgeBase、Application 和 Grant；`409 REQUEST_IN_PROGRESS` 可短暂退避后复用原 Key；`409 IDEMPOTENCY_CONFLICT` 或 `IDEMPOTENCY_CONTEXT_CHANGED` 应生成新 Key 明确发起新请求；`503` 可在业务超时预算内指数退避，Answer 依赖失败时可以降级调用 `/retrieve`。

后台“使用说明”页面提供可复制 curl/Java 示例，并可下载：

- `docquery-service-api.openapi.yaml`
- `docquery-service-api.postman_collection.json`
