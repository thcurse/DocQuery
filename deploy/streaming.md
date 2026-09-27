# 流式回答

知识库问答默认使用真实模型增量输出；API 调试的问答接口提供“普通回答 / 流式回答”选择，默认普通。原 `/answer` 的请求、响应和幂等空间保持兼容。没有新增数据库字段、多轮上下文、断点续传或回答持久化。

## 接口与事件

`POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/answer/stream`，请求 JSON 沿用 `AnswerRequestDTO`。携带 `Authorization: Bearer …`、`Idempotency-Key`，可选 `X-DocQuery-Trace-Id`、`X-DocQuery-Actor-Ref`，并声明 `Accept: text/event-stream`。

```bash
curl --no-buffer --request POST \
  'http://localhost:8080/api/v1/service/knowledge-bases/1/answer/stream' \
  --header "Authorization: Bearer ${DOCQUERY_CREDENTIAL}" \
  --header 'Idempotency-Key: stream-expense-001' \
  --header 'Content-Type: application/json' \
  --header 'Accept: text/event-stream' \
  --data '{"query":"差旅住宿报销标准是什么？","mode":"HYBRID","topK":5}'
```

每个 SSE 事件的 `data` 为 JSON；每 15 秒发送注释心跳。事件结构如下：

| 事件 | 数据 |
| --- | --- |
| `start` | `{"version":1,"requestId":"…"}` |
| `progress` | `{"stage":"retrieving"}`；后续为 `generating`、`validating` |
| `delta` | `{"seq":1,"text":"新增文字"}`，seq 从 1 递增 |
| `done` | `{"result":完整AnswerResponseVO,"replayed":false}` |
| `error` | `{"code":"…","message":"安全提示","retryable":true,"clearPreview":false}` |

`done` 和 `error` 互斥。EOF 没有终止事件时视为中断。仅 `done.result` 建立正式结果和引用；其内容会替换临时预览。证据不足是 `INSUFFICIENT_EVIDENCE` 正常结果。输出错误的 `clearPreview=true` 要求清空预览；其他中断可保留文字，但必须标注“未完成，未经最终校验”。不向客户端传输思考、工具参数、原始 JSON 或内部证据编号。

请求和权限错误在建立 SSE 前返回原 JSON 错误与 HTTP 状态；开始流式响应后的业务错误使用 `error` 事件，审计的实际 HTTP 状态为 200，outcome 仍是失败或 `INTERRUPTED`，不能仅按 HTTP 判断回答成功。应用凭证 401 不影响后台管理员会话。

## 幂等、取消与资源边界

- 普通和流式接口共享 `ANSWER` 幂等空间。相同键和请求参数在执行中返回 409；完成后流式重放只发送 `start → done(replayed=true)`，不会再次调用模型。
- 主动重试保留原幂等键；问题、知识库、应用或检索参数改变后生成新键。客户端不自动重连、不自动重试模型，也不解释 `Last-Event-ID`。
- Redis 仅保存经过最终校验的完整结果。保存结果、成功审计、发送 `done` 按此顺序执行。提交后断线保留缓存；审计失败也不删除已提交结果。重放为独立的 HTTP 审计尝试。
- 停止按钮立即中断本地接收并解除忙碌状态。服务端通过发送失败、断线或心跳获知中断，取消已有模型句柄并屏蔽迟到回调。同步检索或首个模型事件之前无法保证瞬间关闭上游；后续步骤会被禁止，剩余工作受超时约束，运行中的幂等记录保留到工作退出后释放。
- 默认 8 个并发、不排队；每个连接独立有界发送队列和串行写线程。队列溢出中断该请求。租约定期续租，丢失所有权终止执行。生成期间不持有数据库事务。
- 最终作答增量解析顶层 `answer` 字符串，处理任意网络分片、转义和 Unicode；完整原始输出仍交给既有证据校验和答案清理。三种协议使用当前 LangChain4j 1.16.2，不自动重试流式模型，也不伪造分块回退。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DOCQUERY_ANSWER_TOTAL_TIMEOUT` | `300s` | 整个执行预算，心跳不续期 |
| `DOCQUERY_ANSWER_MODEL_TIMEOUT` | `120s` | 单次模型调用上限，并受剩余总预算约束 |
| `DOCQUERY_ANSWER_STREAM_CONCURRENCY` | `8` | 最大流式并发 |
| `DOCQUERY_ANSWER_STREAM_QUEUE_CAPACITY` | `256` | 单连接待发送事件上限 |
| `DOCQUERY_ANSWER_STREAM_MAX_OUTPUT_BYTES` | `1048576` | 原始模型输出字节上限 |

模型仍使用 `docquery.query.answer.chat-profile` 指定的 profile，支持 `CHAT_COMPLETIONS`、`RESPONSES`、`ANTHROPIC_MESSAGES`。需要供应商实际支持对应协议的流式接口。不支持时返回明确错误，不静默调用普通接口。

## Nginx

使用 [nginx.conf](nginx.conf) 中流式路径的独立 location：关闭 `proxy_buffering`、`proxy_cache`、`gzip`，保留 360 秒读取超时。后端设置 `Cache-Control: no-cache, no-transform` 和 `X-Accel-Buffering: no`。其他外层网关也须允许及时转发 SSE；前端和 API 同源，认证机制不变。

取消句柄行为参见 [LangChain4j streaming cancellation](https://docs.langchain4j.dev/tutorials/response-streaming/#streaming-cancellation)；代理行为参见 [Nginx proxy_buffering](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)。

## 本地验证记录（2026-09-26）

- Java 定向测试覆盖增量 JSON / 中文 / emoji / 跨片段内部编号、输出上限、SSE 首段早于完成、超时、并发拒绝、取消与提交、超时与提交竞态、慢客户端队列溢出及名额释放、幂等跨接口重放、审计 HTTP 200 失败。
- 隔离 Docker 依赖的完整浏览器回归通过（22.4s）：租户 / 管理员、上传到 READY、检索、普通回答、真实后端 SSE 流式回答、引用、审计、文档下载与删除；模型端使用本地 Fake Provider。该链路日志记录检索 335ms、首段 790ms、总耗时 1552ms，首段先于模型完成抵达。
- 三个协议均通过真实 LangChain4j SDK 对本地受控 HTTP 服务的增量测试；这不是供应商真实模型验收。
- Vue 类型检查、Lint、28 个单元测试通过；浏览器模拟流程验证了响应式界面、最终引用、提前到达的文字和停止后的部分回答。
- 临时 Nginx 容器通过配置检查。受控上游限速响应经过代理，首段约 31ms 到达，完成约 2.906s，确认未整段缓冲。此记录只证明代理转发能力，不代表真实模型首字延迟。
- 真实本地后端经 Nginx 请求当前配置模型时，检索开始约 0.55s 后抵达，约 4.17s 返回 `ANSWER_MODEL_UNAVAILABLE`。供应商返回认证异常；审计核实为 FAILED、HTTP 200、`ANSWER_MODEL_UNAVAILABLE`。未改动模型和凭证。真实模型增量、取消能力及耗时仍待有效供应商凭证验证，不能用上述模拟测试替代。
- 全量 Java 单元测试有两个既有数据集冻结校验失败：`N3EvaluationDatasetTest` 的文件长度、`N5PublicEvaluationDatasetTest` 的文件 SHA。此次没有修改评测数据；其余执行到的测试无失败。

常用回归命令（Windows 使用 `mvnw.cmd`）：

```text
./mvnw -Dtest=AnswerStream*Test,AnswerDeltaParserTest,AnswerExecutionTest,AnswerServiceImplTest,AuditedQueryServiceImplTest,LangChain4jAnswerAgentAdapterTest,RedisQueryIdempotencyServiceTest test
cd frontend
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm exec playwright test e2e/ui-smoke.spec.ts
```

完整临时依赖链路使用 `tools/n4.4/run-showcase-e2e.ps1`，所有模型请求都固定到本地 Fake Provider，不连接真实模型。验证真实供应商时需分别测试三种协议，记录检索、首段、总耗时、取消至工作退出耗时，只记录 requestId 和耗时，不记录凭证或回答正文。


## Grok 4.7 延迟排查与配置（2026-09-26）

一次用户请求的服务端耗时为 77.378s：检索 Agent 和证据选择 46.056s；最终作答开始后又等待 30.088s 才发出第一个答案片段，剩余输出与提交约 1.234s。前端总耗时 78.044s 与服务端吻合。日志中有两次成功搜索、三次 open 和一次参数无效的搜索；当时没有逐轮模型耗时和原始首段时间，不能据此确定那一次 30 秒全部耗在推理，也无法恢复无效调用的原始参数。

使用 7266 字符的自造产品说明（没有发送知识库文档），对同一个 PackyAPI Grok 4.7 / Responses 接口进行单次对照：

| 推理配置 | 首个原始文本片段 | 总耗时 | 推理 token | 文本片段数 |
| --- | --- | --- | --- | --- |
| 未显式设置 | 12.844s | 20.625s | 663 | 520 |
| `reasoning.effort=low` | 3.016s | 11.407s | 139 | 453 |

这是单次合成输入对照，不是原用户问题的提速验收，也不保证后续问题相同耗时。默认组有 1152 个缓存输入 token，low 组为 0；两次生成长度也略有不同。供应商持续返回文本片段，说明该接口可以真正流式输出。对 Chat Completions 的独立探测返回 HTTP 400 / `protocol_not_supported`，当前 Grok 4.7 配置继续使用 Responses。

[xAI 官方说明](https://docs.x.ai/developers/model-capabilities/text/reasoning)中，Grok 4.7 默认推理强度为 high，支持 low / medium / high / xhigh，不能关闭推理。当前本地 Grok profile 已恢复为 high，优先保持回答质量。上面的 low 结果仅用于历史耗时对照，尚未验证业务答案质量，不作为默认配置。

在 `config/application-secrets.yml` 对应 profile 下配置：

```yaml
docquery:
  chat:
    profiles:
      grok-4.7:
        # 原有 model、protocol、base-url、api-key 等配置继续保留。
        reasoning-effort: high
```

`reasoning-effort` 接入同步和流式 Responses 请求，覆盖检索 Agent 与最终作答；未配置时保持供应商默认行为。历史 `thinking-mode` 字段并不设置 Responses 的推理强度，不能用它代替该参数。

新增日志只记录时长、调用序号、token 数和固定错误分类：

- `docquery_answer_model_call`：每轮同步 Agent 模型调用时间、工具数量和 token 数。
- `docquery_answer_model_stream`：首个推理事件、首个模型文本、首个解析后答案片段、总耗时和片段数量，可区分上游等待与答案解析等待。
- `docquery_answer_tool ... status=INVALID_ARGUMENT reason=...`：参数字段缺失、额外字段、空文本等静态分类；不记录参数内容。

所有日志通过 `queryExecutionId` 与既有检索和终态日志关联。不输出模型思考、问题、文档或答案正文。
