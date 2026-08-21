# DocQuery

面向传统业务系统的知识库服务。N0—N4.3 均已实现、验证并由用户确认完成。

当前支持管理面 multipart 上传，默认以 SeaweedFS 提供 S3 兼容对象存储，并通过 Transactional Outbox 和 RabbitMQ 建立可靠异步投递、重试和 DLQ；支持 PDF、DOCX、TXT、Markdown 的无模型解析，生成 `canonical.jsonl`，再沿真实标题树生成带 2,560 维导航向量的 `retrieval.jsonl`。N2.5 已把原文和导航卡分别投影到 Elasticsearch Evidence/Navigation 索引，完整校验后原子切换 `activeVersionId` 并进入 `READY`。N3.1 增加了服务请求内部 Credential/Grant 前置校验、不可变 activeVersion 快照和 Redis 请求幂等；N3.2 已开放 `/retrieve`；N3.3 已开放受控单轮 `/answer`，使用 Java 管理的有限 Tool Calling、canonical Evidence ID 和严格引用校验；N3.4 已落地查询审计和评测执行器。N4.1 已提供文档、版本、任务查询和失败任务人工重试；N4.2 已提供文档立即检索下线、删除墓碑及全部版本内容的可靠异步清理。N4.3 已提供同源 `/admin/` 管理后台，覆盖平台侧 Tenant 和租户侧 Application、Credential、Grant、KnowledgeBase 核心管理。真实模型、Elasticsearch 投影、生产 Listener 和查询幂等在公共配置中仍默认关闭；文档生命周期页面仍不在 N4.3 范围内。后续技术路线见 [`N2 技术选型总览`](docs/technical-decisions/02-文档检索与异步处理技术选型.md)。

产品边界和后续开发顺序见 [`docs/product`](docs/product)。

## 本地运行

前置条件：JDK 17、Docker Desktop；从源码构建还需要本机 Node.js 和 npm，生产运行打包后的 JAR 不需要 Node.js。

可提交的公共配置位于 `src/main/resources/application.yml`。真实模型调用所需的两个 API Key 保存在本地 `config/application-secrets.yml`，该文件已被 `.gitignore` 排除，并以外部配置方式加载，不会打进 JAR。填写 `chat-api-key`、`embedding-api-key` 和百炼 `embedding-base-url` 后，将 `provider-enabled` 改为 `true`；不要把真实密钥写回公共配置。

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

默认 Compose 分组包含 MySQL、SeaweedFS、RabbitMQ、Elasticsearch、Redis 和生产 DeepDoc GPU HTTP 服务；在 Docker Desktop 中启动整个 `docquery-greenfield` 分组即可启动这六个服务。`deepdoc-p0-cpu`、`deepdoc-p0-gpu` 仍只是显式 profile 下的可复现技术验证任务，不属于默认分组。MySQL 默认发布到 `localhost:3308`，SeaweedFS S3 API 发布到 `localhost:8333`，RabbitMQ 发布到 `localhost:25672`（容器内仍为标准 `5672`，管理端为 `15672`），Elasticsearch 发布到 `localhost:19200`，Redis 只绑定回环地址 `localhost:26379`，DeepDoc 只绑定 `localhost:18080` 并请求 Docker GPU。开发账号和密码均可通过 `DOCQUERY_*` 环境变量替换。生产 HTTPS 环境还必须设置 `DOCQUERY_SESSION_COOKIE_SECURE=true`。

首次部署且数据库中不存在任何管理员时，通过交互式终端创建首个平台管理员：

```powershell
java -jar target\docquery-0.0.1-SNAPSHOT.jar bootstrap-admin --login-name=platform.admin
```

密码会在终端中读取并二次确认，不要把密码放入命令参数。该命令只创建一个 `PLATFORM_ADMIN`，不会创建租户；已有管理员时会拒绝执行。

管理员认证接口位于 `/api/admin/v1/auth`，调用登录和登出前必须先通过 `GET /csrf` 获取 CSRF Token。完整契约和验证证据见 [`docs/development/N1-身份与授权.md`](docs/development/N1-身份与授权.md)。

应用启动后可通过 `http://localhost:8080/admin/` 进入管理后台。前端与后端同源，沿用服务端 Session、CSRF、角色和租户隔离；当前页面范围为 Tenant、Application、Credential、Grant 和 KnowledgeBase 核心管理。

Tenant 管理接口位于 `/api/admin/v1/tenants`。平台管理员可以创建、分页查看和更新 Tenant；租户管理员只能查看自己的 Tenant。所有修改请求都必须携带登录后重新获取的 CSRF Token。

Application 和 KnowledgeBase 管理接口位于 `/api/admin/v1/tenants/{tenantId}/applications` 与 `/api/admin/v1/tenants/{tenantId}/knowledge-bases`，只接受所属租户的租户管理员，不接受平台管理员跨租户代管。

Application Credential 管理接口位于 `/api/admin/v1/tenants/{tenantId}/applications/{applicationId}/credentials`，支持创建、分页查看和幂等撤销。完整凭证只在创建成功响应中返回一次，数据库只保存 Secret 的 SHA-256 摘要；同一 Application 最多同时保留两把有效凭证。

ApplicationGrant 管理接口位于 `/api/admin/v1/tenants/{tenantId}` 下，支持按 Application 与 KnowledgeBase 建立、查看和撤销授权。权限代码为 `1=READ`、`2=WRITE`、`3=READ_WRITE`；服务面检索会先执行 N3.1 的凭证、授权、activeVersion 快照和 Redis 幂等边界，再进入 N3.2 的真实检索链路。

真实文档上传接口位于 `/api/admin/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents` 及其 `/{documentId}/versions` 子路径，要求管理员 Session、CSRF、`Idempotency-Key` 和 multipart 文件；单文件上限默认 50 MiB。完整契约与边界见 [`docs/development/N2.2-对象存储与异步投递.md`](docs/development/N2.2-对象存储与异步投递.md)。

N2.3 标准化能力由内部 `DocumentCanonicalService` 提供；N2.5 完整 Processor 已将它接入异步链路。解析范围与验证证据见 [`docs/development/N2.3-文档解析与标准化.md`](docs/development/N2.3-文档解析与标准化.md)。

N2.4 已实现使用 DeepSeek 官方 `deepseek-v4-flash` 生成真实标题检索卡，使用阿里云百炼 `qwen3.7-text-embedding` 生成 2,560 维导航向量，并保存单一 `retrieval.jsonl` 派生对象及 V7 MySQL 清单。供应商 API Key 不进入仓库；真实调用需显式开启，DeepSeek + 百炼小样本冒烟测试已于 2026-08-10 执行通过，N2.4 同日经用户确认完成。详见 [`docs/development/N2.4-检索卡与导航向量.md`](docs/development/N2.4-检索卡与导航向量.md)。

N2.5 已实现 Elasticsearch 9.4.4 双索引、V8 投影验收单、确定性幂等重建和版本原子激活，并以真实 MySQL、SeaweedFS、RabbitMQ、Elasticsearch + Fake Gateway 完成端到端验证；用户已于 2026-08-11 确认完成。详见 [`docs/development/N2.5-双索引投影与版本激活.md`](docs/development/N2.5-双索引投影与版本激活.md)。

N3.1 已实现每次请求重新校验 Application Credential、READ Grant 和 KnowledgeBase 状态，用一条 MySQL 查询固定不可变 activeVersion 快照，并以 Redis Lua 提供 owner token、续租、冲突和短期成功重放；用户已于 2026-08-11 确认完成。该阶段尚未开放 Retrieve/Answer。详见 [`docs/development/N3.1-查询授权与Redis幂等.md`](docs/development/N3.1-查询授权与Redis幂等.md)。

N3.2 已实现 `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/retrieve`：支持 `KEYWORD`、`SEMANTIC`、`HYBRID`，使用 2,560 维查询向量、Evidence BM25、Navigation KNN、Java RRF 和 canonical 原文引用，并在语义支路失败时对 HYBRID 做明确关键词降级；用户已于 2026-08-11 确认完成。详见 [`docs/development/N3.2-BM25与KNN检索及原文引用.md`](docs/development/N3.2-BM25与KNN检索及原文引用.md)。

N3.3 已实现 `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/answer`：先按原问题完成一次 N3.2 检索，再由 Java 在固定权限/版本快照和硬预算内控制四个只读工具；最终只接受 `ANSWERED` 或 `INSUFFICIENT_EVIDENCE`，并校验每个 Evidence ID 和 canonical 引用。实现与全量验证已通过，用户已于 2026-08-11 确认完成；审计和评测仍属于 N3.4。详见 [`docs/development/N3.3-受控单轮Answer与只读工具.md`](docs/development/N3.3-受控单轮Answer与只读工具.md)。

N3.4 已实现服务面查询审计、管理面审计查询、确定性评测执行器和性能脚本；`n3-eval-v1` 只作为格式、链路和指标冒烟夹具。成熟公开数据集的真实供应商质量、成本与端到端延迟评测尚未执行，继续作为完整功能落地后的发布前最终门禁。详见 [`docs/development/N3.4-查询审计与检索评测基线.md`](docs/development/N3.4-查询审计与检索评测基线.md)。

N4.1 已实现租户管理面的文档列表/详情、版本历史、ProcessingJob 列表/详情和最终失败任务人工重试，且新 attempt 成功前旧 activeVersion 持续服务；用户已于 2026-08-12 确认完成。详见 [`docs/development/N4.1-文档版本任务查询与失败重试.md`](docs/development/N4.1-文档版本任务查询与失败重试.md)。

N4.2 已实现 `DELETE /api/admin/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}` 及删除失败人工重试：受理事务立即清空 `activeVersionId`，独立 RabbitMQ 链路幂等清理全部版本的双索引、canonical/retrieval 和原文件，最终保留墓碑与历史并释放名称；用户已于 2026-08-12 确认完成。详见 [`docs/development/N4.2-文档安全删除与异步清理.md`](docs/development/N4.2-文档安全删除与异步清理.md)。

N4.3 已实现 React + TypeScript 管理后台并打入同一个 Spring Boot JAR：平台管理员管理 Tenant，租户管理员管理 Application、Credential、Grant 和 KnowledgeBase；完整 Credential 只在创建后一次性展示。用户已于 2026-08-15 确认完成。详见 [`docs/development/N4.3-管理后台前端基础与核心资源管理.md`](docs/development/N4.3-管理后台前端基础与核心资源管理.md)。

工程代码按 `Controller -> Service 接口 -> service.impl -> Mapper -> Entity` 组织；DTO 使用普通 class、Lombok 和 Jakarta Validation 承载请求或查询数据，VO 使用只读普通 class 承载接口响应，Entity 不直接返回。详细规则见 [`docs/development/01-工程代码分层规范.md`](docs/development/01-工程代码分层规范.md)。

完整验证会通过 Testcontainers 启动临时 MySQL、SeaweedFS、RabbitMQ、Elasticsearch 和 Redis，不连接 Compose 持久数据。2026-08-13 的默认免费回归包含 144 项 Java 测试：142 项执行通过，2 项真实供应商冒烟按设计跳过，0 failure、0 error；同一 Maven 生命周期中的前端 Vitest 为 8/8：

```powershell
.\mvnw.cmd clean verify
```

停止本地服务（保留四个命名卷；Redis 本身没有持久卷）：

```powershell
docker compose stop
```

该命令不会退出 Docker Desktop，也不会删除任何数据卷。
