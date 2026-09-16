# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

DocQuery 是面向传统业务系统后端接入的多租户 B2B 知识库服务（不是面向终端用户的聊天产品）。业务系统携带 Application Credential 并指定唯一 `knowledgeBaseId` 调用检索/回答接口；DocQuery 负责租户隔离、凭证与授权、文档全生命周期、检索与回答、以及查询审计。技术栈：Spring Boot 4.1.0 + Java 17 + MyBatis + Flyway + MySQL；S3 兼容对象存储（默认 SeaweedFS）；RabbitMQ + Transactional Outbox 做可靠异步处理；Elasticsearch 9.4.4 做双索引检索；Redis 做查询幂等；LangChain4j 接入 Chat/Embedding 模型；React 19 + TypeScript + Vite + Ant Design 管理后台，构建产物打入同一个 Spring Boot JAR（生产是单进程，不需要独立 Node 服务）。

仓库处于按 N0 → N5.x 分阶段推进、每阶段需用户明确确认才能进入下一阶段的开发流程中。**新会话开始严肃开发工作前，先完整阅读 [`docs/product/00-DocQuery-新服务交接说明.md`](docs/product/00-DocQuery-新服务交接说明.md)**：它记录了当前哪些阶段已确认完成、哪些证据仍是冒烟性质不可外推、以及"未经明确授权不得扩大付费评测或自动进入下一阶段"这条硬性协作规则。不要从代码状态或历史印象直接推断当前授权范围。

## 构建、测试、运行

前置条件：JDK 17、Docker Desktop；从源码构建还需要本机 Node.js/npm（生产运行打包后的 JAR 不需要）。

```bash
# 完整验证：Java 编译 + 单元测试(Surefire) + 集成测试(Failsafe, 真实 Testcontainers) + 前端 npm ci/build/vitest，全部绑定在同一 Maven 生命周期
./mvnw.cmd clean verify

# 只跑 Java 单元测试（*Test.java，走 Surefire，test 阶段）
./mvnw.cmd test -Dtest=ClassName

# 只跑某个集成测试（*IT.java，走 Failsafe，需要 Docker 可用于 Testcontainers）
./mvnw.cmd "-Dit.test=ClassName" verify

# 前端（frontend/ 目录下）
npm run dev        # vite dev server，代理 /api 到 127.0.0.1:8080
npm run build       # tsc --noEmit && vite build
npm test            # vitest run；单文件: npm test -- src/api/client.test.ts
npm run test:e2e     # playwright，需要一个真实运行中的后端（见 tools/n4.3、tools/n4.4 的 PowerShell 编排脚本）

# 本地依赖服务（compose 项目名 docquery-greenfield）
docker compose up -d
docker compose stop   # 不删除数据卷
```

集成测试使用 Testcontainers 启动**临时** MySQL/RabbitMQ/Elasticsearch/Redis，从不连接 `docker compose` 起的持久化服务；Chat/Embedding 等真实供应商默认由 Fake Gateway 替代（`@Import` 注入 Fake 配置类），少数标记为真实供应商冒烟的测试默认跳过（不是失败）。

首次部署创建平台管理员：`java -jar target\docquery-*.jar bootstrap-admin --login-name=xxx`（交互式输入密码，已存在管理员时拒绝执行）。

本地默认端口：MySQL `3308`、SeaweedFS S3 `8333`、RabbitMQ `25672`（管理界面 `15672`）、Elasticsearch `19200`、Redis `26379`（仅回环）、DeepDoc HTTP `18080`（仅回环）、应用本身 `8080`。均可通过 `DOCQUERY_*` 环境变量覆盖。真实模型调用的 API Key 放在本地 `config/application-secrets.yml`（已 gitignore，外部配置导入，不打入 JAR），不要把密钥写回 `application.yml`。

## 架构

### 分层约定（强制，见 [`docs/development/01-工程代码分层规范.md`](docs/development/01-工程代码分层规范.md)）

`Controller -> Service 接口 -> service.impl（类名以 Impl 结尾） -> Mapper -> Entity`。Controller 只做参数绑定和调用 Service，不碰 Mapper/Entity；`dto` 只承载请求/查询输入；`vo` 是唯一对外响应模型（只读）；`entity` 与数据库表一一对应，从不直接返回；`service` 目录只放接口，实现全部在 `service.impl`。状态/角色/权限类字段统一用字符串数字代码持久化（如 `GrantPermission` "1"/"2"/"3"、`AdminRole` "1"/"2"），不用位掩码或裸布尔；前端 `RoleGate`/`domain/types.ts` 沿用同一套字符串代码。数据库不使用物理外键或 CHECK 约束，关联和状态一致性完全由 Service 代码与集成测试保证。

### 身份与管理面

`Tenant -> Application -> Credential -> ApplicationGrant -> KnowledgeBase`。`PLATFORM_ADMIN` 管理 Tenant（含创建租户管理员账号）；`TENANT_ADMIN` 只能管理自己租户下的 Application/Credential/Grant/KnowledgeBase，两者用各自独立的 Controller/Service/Mapper，没有笼统的"资源管理"服务合并 Application 和 KnowledgeBase。管理面认证是服务端 Session（`JSESSIONID`，HttpOnly + SameSite=Lax）+ CSRF（登录/登出/任何写请求前必须先 `GET /api/admin/v1/auth/csrf`）；`AdminSessionValidationFilter` 每次请求都重新校验数据库中账号/租户/角色是否仍然有效，不是只信任 Session 内容。Credential 完整值只在创建成功响应中一次性返回，数据库只存 SHA-256 摘要；同一 Application 最多同时保留两把有效凭证。`ApplicationGrant.permission` 是字符串数字代码，不是位掩码。

### 文档入库管道（异步，Outbox 模式）

```
管理面 multipart 上传
→ SourceObjectStore 保存原文件（S3 兼容，默认 SeaweedFS）
→ 单个 MySQL 事务原子创建 Document + DocumentVersion + ProcessingJob + OutboxEvent
→ OutboxPublisher 定时租约领取未发布事件，经 Publisher Confirm 发到 RabbitMQ
→ RabbitMQ 主队列（durable）+ 三级 TTL 重试队列（5s/30s/300s）+ DLQ
→ DocumentProcessingListener 消费 → DocumentIngestionProcessorImpl 顺序执行四个阶段
```

`DocumentIngestionProcessorImpl` 的四个阶段中，前三个必须幂等且在 MySQL 事务**之外**执行（模型调用、对象存储、ES 网络等待不能占用数据库锁），只有最后一步是短事务：

1. `DocumentCanonicalService`：按显式格式解析。生产默认统一走 `DeepDocDocumentParser`（HTTP 调用项目内 DeepDoc GPU 服务，PDF/DOCX/TXT/Markdown 都走它，`docquery.parsing.deepdoc.enabled=true` 是默认值）；四个本地专属 Adapter（`PdfDocumentParser`/`DocxDocumentParser`/`TextDocumentParser`/`MarkdownDocumentParser`）只有显式设 `enabled=false` 才会启用，二者是互斥的 `@ConditionalOnProperty` 二选一，不会同时注册。输出按源顺序的 `EvidenceBlock`（真实标题树 + 原始位置），写成不可变 `canonical.jsonl`，MySQL 只存清单和摘要。
2. `DocumentRetrievalService`（`RetrievalArtifactGenerator`）：LangChain4j Chat 模型沿真实标题树自底向上生成一张 `DocumentProfile`（根）和每个非根标题一张 `RetrievalNode` 检索卡；Embedding 模型生成 2,560 维导航向量。只使用文件已有的真实标题，不造虚拟目录；产物是单一 `retrieval.jsonl`。
3. `DocumentSearchProjectionService`：写 Elasticsearch 双索引——Evidence 索引对原文做 BM25，Navigation 索引对检索卡做 `dense_vector` KNN；确定性 ID + Bulk，支持重复投影恢复和完整性复核。ES 不保存"当前生效"标志。
4. `DocumentVersionActivationService`：唯一的短 DB 事务，原子切换 `Document.activeVersionId` 并把 `DocumentVersion` 置为 `READY`。`activeVersionId` 是唯一可见性事实。

删除走独立镜像管道（`DocumentDeletionListener` / `DocumentDeletionRabbitMqTopology`，同样的租约 + 三级重试 + DLQ 结构）：受理即在事务内清空 `activeVersionId`（新查询立即不可见），异步清理全部历史版本的 ES 投影、canonical/retrieval 派生对象和原文件，全部成功后才置 `DELETED` 并释放文档名；失败时文档保持 `DELETING`，绝不恢复可见性。人工重试失败任务复用同一 `DocumentVersion`，递增 `attemptNo`，旧 `activeVersion` 在新 attempt 成功前持续对外服务。

注意区分两层重试：`OutboxPublisher` 内部对"发布到 Broker"失败的重试是无限次数退避（1s/5s/30s/120s/600s + 抖动，之后重新变为可发布，不进 DLQ）；RabbitMQ Consumer 对"处理消息"失败的重试才是有限的三级 TTL 队列 + DLQ（`MessagingProperties.retry-delay-1/2/3`）。这是两个独立的失败恢复机制，不要混用配置。

### 查询与回答管道（同步 HTTP，对外服务面 `/api/v1/service/**`）

每个请求：`Authorization: Bearer <Application Credential>` → `ApplicationCredentialResolver` 重新校验凭证/应用/租户 → `KnowledgeBaseAccessAuthorizer` 校验 READ Grant → 用**一条** MySQL 查询把当前全部 READY 的 `activeVersionId` 固定成不可变的 `QueryAccessContext`（含快照指纹）→ Redis（Hash + Lua 原子操作）处理 `Idempotency-Key`：owner token、RUNNING/SUCCEEDED 状态、续租、失败释放、短期成功重放；`docquery.query.idempotency.enabled` 默认关闭，关闭时不做幂等；开启后 Redis 不可用则 fail-closed。

- `RetrieveServiceImpl`：`KEYWORD`（ES BM25）/ `SEMANTIC`（查询向量 KNN）/ `HYBRID`（两路 + Java 端 RRF 融合，`score = 1/(rrfK+keywordRank) + 1/(rrfK+semanticRank)`）。结果携带可回溯的原文引用（含物理页等 `SourcePosition`）。`HYBRID` 在语义支路失败时显式降级为纯关键词（响应带 `degraded`/`degradationReason`）；显式指定 `SEMANTIC` 时依赖失败是硬错误，不会伪装成空结果。`ScopedRetrievalService.retrieveForAnswer` 是 Answer 内部专用入口，可以有不同的候选池排列，但不改变公开 `/retrieve` 契约。
- `AnswerServiceImpl`：先按原问题跑一次内部 Retrieve，再交给受限 LangChain4j Agent（当前通过 PackyAPI Responses 协议接入，模型名配置在 `docquery.chat.profiles.<model>.*`，profile key 必须等于模型名）。Agent 只有三个工具，参数全部是服务端注册的不透明引用（模型永远拿不到租户/知识库/版本/凭证/对象 Key）：
  - `search`——在固定快照内检索候选章节，返回 `D#`（文档）/`S#`（章节）/`E#`（证据锚点）引用，支持游标翻页；
  - `open`——展开 `D#`（真实大纲）/`S#`（最小授权章节，可能带子章节引用）/`E#`（展开到页或上下文，返回 `R#`）/`R#`（读取该页或窗口）；
  - `submit_evidence`（`ReturnBehavior.IMMEDIATE`）——终止检索，把选中的 `evidenceIds`/`readRefs` 交给独立的最终生成环节，不在这个工具里直接作答。
  
  Java 侧固定权限/版本快照、工具白名单、循环与成本预算（轮次/调用次数/模型调用次数/总超时，见 `docquery.query.answer.*` / `AnswerProperties`）。最终结果只接受 `ANSWERED`（必须逐段引用已登记的 canonical Evidence ID，未通过引用校验会被拒绝）或 `INSUFFICIENT_EVIDENCE`（HTTP 200 的合法终态，不是错误）。
- `AuditedQueryService` 是 Retrieve/Answer 对外的唯一入口：fail-closed 审计——成功响应必须等审计行落库后才能返回；无效凭证不会产生审计行；成功/拒绝/依赖失败/幂等冲突/降级/重放各自有独立的审计分类，不能互相冒充。

### 配置

所有配置集中在 `docquery.*`（`src/main/resources/application.yml`），每项都有 `DOCQUERY_*` 环境变量覆盖点和开发期安全默认值。真实外部调用默认关闭：`docquery.retrieval.provider-enabled`、`docquery.search.enabled`、`docquery.query.idempotency.enabled`、`docquery.messaging.listener-enabled` 都需要显式打开，避免误触发外部计费或引入 ES/MQ 依赖。改配置前先确认是本项目在改，不要套用其他工程的 JDK/Maven 路径假设。

### 前端

`frontend/`：React 19 + TypeScript + Vite + Ant Design 6 + TanStack Query + React Router（`createHashRouter`，data mode，路由懒加载）。构建由根 `pom.xml` 的 `exec-maven-plugin` 驱动（`generate-resources` 阶段 `npm ci` + `vite build`，`test` 阶段 `npm test`），产物由 `maven-resources-plugin` 复制进 `target/classes/static/admin`，与后端同源运行在 `/admin/`，登录态、CSRF、角色和租户隔离全部沿用后端会话，没有独立的前端鉴权体系。身份唯一事实来源是 `/auth/me`；`RoleGate` 按 `admin.role` 字符串代码（'1'=平台管理员、'2'=租户管理员）控制菜单和路由可见性，与后端 `AdminRole` 编码一致。E2E 测试（`frontend/e2e/`，Playwright）需要一个真实运行的打包 JAR + 临时 MySQL，由 `tools/n4.3/run-admin-e2e.ps1`、`tools/n4.4/run-showcase-e2e.ps1` 编排（仅 PowerShell，Windows）。

### 评测与工具脚本

`evaluation/` 存放固化的评测语料和报告（`n3-eval-v1` 是格式/链路冒烟夹具，`mmlongbench-docquery-v1` 是基于 MMLongBench-Doc 的真实 PDF 可靠性压力集，均非官方 Gold Set）；`tools/evaluation/` 下的 Python 脚本负责构建语料子集和跑检索/回答评测；`tools/deepdoc-service/`、`tools/document-parser-p0/` 是把 DeepDoc/RAGFlow 能力封装成 DocQuery 内部 HTTP 解析服务的相关代码和 Dockerfile。改动这些脚本或扩大评测范围前，先看 `docs/product/00-...` 里当前授权到哪一步。
