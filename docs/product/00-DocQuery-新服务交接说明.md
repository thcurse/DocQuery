# DocQuery 新服务交接说明

> 最后更新：2026-08-24
> 当前检查点：N0—N4.4、N5.1-P0/P1/P2 与 N5.2-R1 已经用户确认关闭；N5.2-R3 已执行并待用户验收；N5.3-A7 固定 5 题复验失败并回滚到 A6 运行基线
> 当前验证：Java 180 项中 178 项通过、2 项真实供应商冒烟按设计跳过，0 failure、0 error，前端 Vitest 14/14，DeepDoc 10/10；评测知识库 100/100 READY；N5.2-R3 的 80/80 个真实 `/retrieve` 请求成功；A7-v2 人工正确 0/5
> 下一步：只讨论 Answer Agent 的最小任务完成约束；不得自动扩大到 20/80 题、继续付费评测或开始多轮 Conversation

## 1. 新会话必须遵守的协作方式

- 先完整阅读本交接文档，再按第 2 节规定的顺序阅读其余文档。
- 每个子阶段开始前，先与用户确认范围、非目标、数据、接口、异常和验收点，并在 `docs/development` 中形成该阶段文档。
- 用户明确说“确认开始 N?.?”后才能修改代码、依赖、数据库迁移或 Compose。
- 实现完成并给出测试证据后必须停下，等待用户明确确认完成；不能自动进入下一阶段。
- 保持最小文件集合，不为“以后可能需要”提前创建目录、类、表或基础设施。
- 用户提出疑问时先解释和收敛设计，不要一边解释一边继续实现。
- 不覆盖或删除用户已有文件。当前仓库尚未形成 Git 基线，`git status --short` 会把整个工程显示为未跟踪文件，不能据此清理工程。

## 2. 必读文档顺序

完成本文件后，按顺序阅读产品文档：

1. [DocQuery 产品设计 v0.1](./01-DocQuery-产品设计-v0.1.md)
2. [DocQuery 管理后台页面说明 v0.1](./02-DocQuery-管理后台页面说明-v0.1.md)
3. [DocQuery 新服务开发计划 v0.1](./03-DocQuery-新服务开发计划-v0.1.md)
4. [管理后台界面稿说明](./mockups/README.md)

再按顺序阅读独立技术选型：

1. [身份与授权技术选型](../technical-decisions/01-身份与授权技术选型.md)
2. [N2 技术选型总览](../technical-decisions/02-文档检索与异步处理技术选型.md)
3. [对象存储与异步处理技术选型](../technical-decisions/02-01-对象存储与异步处理技术选型.md)
4. [文档解析与标准化技术选型](../technical-decisions/02-02-文档解析与标准化技术选型.md)
5. [检索生成与查询技术选型](../technical-decisions/02-03-检索生成与查询技术选型.md)

需要了解已实现代码的契约和证据时再阅读：

1. [工程代码分层规范](../development/01-工程代码分层规范.md)
2. [N1 身份与授权实现文档](../development/N1-身份与授权.md)
3. [N2.1 文档数据模型与上传受理](../development/N2.1-文档数据模型与上传受理.md)
4. [N2.2 对象存储与异步投递](../development/N2.2-对象存储与异步投递.md)
5. [N2.3 文档解析与标准化](../development/N2.3-文档解析与标准化.md)
6. [N2.4 检索卡与导航向量设计](../development/N2.4-检索卡与导航向量.md)
7. [N2.5 双索引投影与版本激活](../development/N2.5-双索引投影与版本激活.md)
8. [N3.1 查询授权、版本快照与 Redis 幂等](../development/N3.1-查询授权与Redis幂等.md)
9. [N3.2 BM25、KNN、RRF 与原文引用](../development/N3.2-BM25与KNN检索及原文引用.md)
10. [N3.3 受控单轮 Answer 与只读工具](../development/N3.3-受控单轮Answer与只读工具.md)
11. [N3.4 查询审计、检索评测与性能基线](../development/N3.4-查询审计与检索评测基线.md)
12. [N4.1 文档、版本、任务查询与失败重试](../development/N4.1-文档版本任务查询与失败重试.md)
13. [N4.2 文档安全删除与异步清理](../development/N4.2-文档安全删除与异步清理.md)
14. [N4.3 管理后台前端基础与核心资源管理](../development/N4.3-管理后台前端基础与核心资源管理.md)
15. [N4.4 完整展示流程与外部 API 交付](../development/N4.4-完整展示流程与外部API交付.md)
16. [N5.1 MMLongBench-Doc 真实 PDF 适配](../development/N5.1-MMLongBench-Doc真实PDF适配.md)
17. [N5.1-P0 DeepDoc 技术验证](../development/N5.1-P0-DeepDoc技术验证.md)
18. [N5.1-P1 DeepDoc HTTP 服务与单队列接入](../development/N5.1-P1-DeepDoc-HTTP服务与单队列接入.md)
19. [N5.1-P2 测试科技租户完整入库与评测集冻结](../development/N5.1-P2-测试科技租户完整入库与评测集冻结.md)
20. [N5.2-R1 检索 PILOT](../development/N5.2-R1-检索PILOT.md)

## 3. 工程与目录现状

```text
D:\IdeaProjects\DocQuery-old    旧工程，只读实验参考
D:\IdeaProjects\DocQuery        当前全新服务，唯一开发目标
```

- 不复制旧工程的代码、配置、数据库结构或 `.git`。
- 旧工程最多用于了解早期解析、Embedding 和 Elasticsearch 实验遇到的问题。
- 当前工程是一个 Spring Boot 单体：一个仓库、一个 `pom.xml`、一个启动类、一个运行进程。
- 当前使用 JDK 17、Spring Boot 4.1.0、MyBatis 4.1.0、Flyway 和 MySQL 8.4.10。
- 当前 `frontend` 使用 React 19、TypeScript、Vite、React Router Data Mode、TanStack Query 和 Ant Design；Maven 使用本机 Node/npm 安装、测试、构建，再把产物打入 Spring Boot JAR 的 `/admin/`，生产无需独立 Node 进程。
- `compose.yaml` 当前默认包含 MySQL 8.4.10、SeaweedFS 4.40、RabbitMQ 4.3.4-management、Elasticsearch 9.4.4、无持久卷的 Redis 8.8.1 和生产 DeepDoc GPU HTTP 服务，均为 `restart: "no"`；DeepDoc P0 CPU/GPU 仍是显式技术验证 profiles，不属于默认分组。RabbitMQ、Elasticsearch、Redis 本地默认发布到宿主 `25672`、`19200`、`26379`，DeepDoc 只发布到 `127.0.0.1:18080`。在 Docker Desktop 启动整个 `docquery-greenfield` 分组即可启动六个默认依赖服务，Compose 不自动启动 IDEA 中的 DocQuery Java 应用。
- 当前 `pom.xml` 已引入 Spring AMQP、Spring Data Redis/Lettuce、AWS SDK for Java 2.47.5 S3/Apache5 Client、Apache PDFBox 3.0.8、Apache POI 5.5.1、CommonMark Java 0.28.0、LangChain4j OpenAI 1.16.2 和 Elasticsearch Java Client 9.4.4。

## 4. 已确认的产品边界

- DocQuery 是供传统业务系统后端接入的多租户 B2B 知识库服务，不是面向终端用户的聊天产品。
- 传统业务系统负责终端用户登录、角色、部门和业务数据权限。
- DocQuery 负责租户隔离、Application Credential、Application 到 KnowledgeBase 的 Grant、文档生命周期、检索和应用访问审计。
- 业务后端携带应用凭证，并明确指定一个 `knowledgeBaseId`；第一版不自动选择知识库，也不跨多个知识库查询。
- DocQuery 从凭证解析可信的 `tenantId` 和 `applicationId`，不能信任请求自行声明的租户身份。
- `ApplicationGrant.permission` 使用字符串数字代码：`"1"=READ`、`"2"=WRITE`、`"3"=READ_WRITE`，不是位掩码。
- 数据库不使用物理外键，也不使用 `CHECK` 承担业务规则；关联、状态和跨租户约束由 Service 业务代码和集成测试保证。

## 5. 实现与验收状态

### 5.1 N0

- Greenfield Spring Boot 工程、Maven Wrapper、Docker MySQL 和 Flyway 基线。
- Java 版本固定为 17。
- 数据库迁移当前为 `V1` 至 `V11`；V4—V8 分别属于 N2.1—N2.5，V9 属于 N3.4 查询审计，V10 属于 N4.1 管理查询和人工重试，V11 属于 N4.2 文档删除。

### 5.2 N1.1 至 N1.7

- 本地管理员账号、bcrypt 密码哈希、Spring Security 和 JSON 登录/登出接口。
- 管理员登录状态使用 JVM `HttpSession`，超时为 8 小时；浏览器使用 `HttpOnly`、`SameSite=Lax` 的 `JSESSIONID`，修改请求保留 CSRF 防护。
- 首个平台管理员通过部署期交互命令创建，不存在公开注册或初始化接口。
- Tenant 的创建、分页查询和更新。
- Application 与 KnowledgeBase 的创建、分页查询和更新，强制租户隔离。
- Application Credential 的创建、一次性展示、分页查看和幂等撤销；数据库只保存高熵 Secret 的 SHA-256 摘要。
- ApplicationGrant 的创建、查看和撤销，以及 `READ`、`WRITE`、`READ_WRITE` 授权判断。
- 已提供内部 Application Credential 解析器和 KnowledgeBase 授权判断组件，并已开放带此前置边界的服务面 `/retrieve` 检索接口。
- 代码分层固定为 `Controller -> Service 接口 -> service.impl -> Mapper -> Entity`。
- DTO 使用普通 class、Lombok 和 Jakarta Validation 承载请求或查询参数；VO 承载响应；Entity 对应数据库表且不直接对外返回。

### 5.3 当前验证

- 当前默认免费回归命令为 `./mvnw.cmd clean verify`；2026-08-16 的最终 XML 报告显示 37 个 Java 测试套件共发现 155 项，153 项执行通过，2 项真实供应商冒烟按设计跳过，0 failure、0 error；Surefire 50 项中 48 项通过、2 项跳过，Failsafe 105/105。同一 Maven 生命周期中的前端 Vitest 为 3 个文件、13/13 通过。2026-08-10 另行显式执行的 N2.4 真实 DeepSeek + 百炼冒烟 1/1 通过；N3.3 真实 Answer Tool Calling 和基于 N5.1 输入的真实供应商效果评测均未在本次验证中执行。
- N5.1-P2 已在“测试科技”租户完成 100 份语料的 DeepDoc、真实检索卡、百炼 Embedding、双索引和激活：100/100 READY、80/80 case 保留，Evidence 78,062/78,062，Navigation 2,422/2,422。P2 已由用户确认完成。
- N5.2-R1 已通过专用 Application Credential 对固定 10 道可回答题分别执行 KEYWORD、SEMANTIC、HYBRID，共 30/30 个正式 `/retrieve` 请求成功，0 重试、0 降级、0 失败。HYBRID 的 Document Recall@5=`1.0000`、任意 Gold 页命中率@10=`1.0000`、Evidence Page Coverage@10=`0.8333`、MRR@10=`0.9333`；用户于 2026-08-20 明确确认 R1 完成。未调用 `/answer` 或 DeepSeek，未执行剩余 54 道保留题。
- N5.1-P1 另有 DeepDoc Java Adapter 6/6、HTTP 服务 4/4、Compose 配置校验和四格式 HTTP 冒烟证据。最终容器位于 `docquery-greenfield`，状态 `healthy`、`RestartCount=0`、`OOMKilled=false`；Ready 显示单解析槽位、四个支持格式和四类实际 CUDA ONNX Session。真实 PDF 冒烟仅使用既有 49 页复杂 PDF 一份，得到 548 个带页位置的中立块，约耗时 73—75 秒；最终镜像又各用 1 份既有 DOCX、TXT、Markdown 冒烟夹具校验格式分发和位置，没有扩大质量样本或调用付费供应商。
- N4.1 曾记录的 137 项来自未 clean 的报告目录，其中包含一个 7 项旧 Surefire XML；有效 N4.1 基线为 130 项，N4.2 新增 12 项后为 142 项，N4.3 新增 2 项 Java 测试后为 144 项，N4.4 原范围新增 1 项静态交付物测试后为 145 项，租户管理员补充再新增 3 项集成测试后为 148 项。该差异是报告统计更正和阶段增量，不是测试文件丢失或能力回退。
- 集成测试使用临时 MySQL 8.4.10、SeaweedFS 4.40、RabbitMQ 4.3.4、Elasticsearch 9.4.4 和 Redis 8.8.1，不连接 Compose 持久数据。
- N4.3 另以 Microsoft Edge 对真实 Spring Boot JAR + 临时 MySQL 执行核心流程验证；N4.4 又在临时 MySQL、SeaweedFS、RabbitMQ、Elasticsearch、Redis 和 Fake Gateway 上完成从上传到 `READY`、Retrieve/Answer、查询审计、新版本切换和删除的真实浏览器验证。N4.4 静态交付物已随 JAR 打包，临时容器均已清理。
- N1 已由用户明确确认完成，后续不能把 N1 的未实现项误报为已有能力。

### 5.4 N2.1 已完成并验收

- V4 新增 `document`、`document_version`、`processing_job`、`outbox_event` 四张表。
- 已实现四表的 Entity、Mapper、字符串数字代码枚举、DTO、VO 和 `DocumentUploadAcceptanceService`。
- 首次受理原子创建 Document、Version、Job、Outbox；新版本锁定 Document、保持旧 activeVersion，并保证最多一个处理中候选版本。
- 上传幂等使用 Tenant 范围的幂等键 SHA-256 和请求指纹；并发数据库冲突完整回滚后读取既有结果。
- N2.1 专项 17/17、完整回归 46/46 通过。
- N2.1 仍没有真实二进制上传 Controller、对象存储、MQ、解析或索引能力，不能描述为已经支持文件上传。
- 用户已于 2026-08-02 明确确认“N2.1 完成”。该确认只结束 N2.1，不授权自动进入 N2.2。

### 5.5 N2.2 已完成并验收

- 管理面两个 multipart 路由已经可以保存真实 PDF、DOCX、TXT 和 Markdown 原文件，再调用 N2.1 事务受理并返回 `202 Accepted`。
- 业务层只依赖 `SourceObjectStore`；首个实现使用 AWS SDK v2 S3 Client，本地/测试默认服务端为 SeaweedFS，单文件上限为 50 MiB。
- 已实现数据库失败精确补偿删除、幂等重放多余对象删除和 fail-closed 孤立对象回收。
- Outbox Publisher 已实现租约领取、Confirm/Return、失败退避和过期恢复；RabbitMQ 已有 durable v1 主队列、三级 TTL 重试队列和 DLQ。
- Consumer 已实现可信事实交叉校验、ProcessingJob 租约/心跳、重复消息、5/30/300 秒三级重试和最终 FAILED/DLQ 骨架。
- 生产 Listener 默认关闭，因为 N2.2 不提供 no-op `DocumentIngestionProcessor`；Outbox 可发布，消息在 durable 主队列等待后续完整处理器。
- N2.2 专项 12/12、完整回归 62/62 通过；1/10/50 MiB 本机对象存储样本已记录在 N2.2 实施文档。
- N2.2 没有解析正文、生成检索卡、写 Elasticsearch、切换 `activeVersionId` 或产生 `READY`。
- 用户已于 2026-08-03 明确确认“N2.2 完成”；该确认只关闭 N2.2，不授权启动 N2.3。

### 5.6 N2.3 已完成并验收

- V6 新增 `document_canonical_artifact`，MySQL 只保存一版本一清单、对象位置、计数和完整性摘要；全文保存为单个不可变 `canonical.jsonl` 派生对象。
- 已实现 PDF、DOCX、Markdown 和 TXT 的格式专属无模型解析；输出按源顺序排列的 `EvidenceBlock`、真实标题树、原始位置和受控 Warning。
- PDF 支持普通文本型单栏的物理页定位，以及能够映射到可见文本的 Tagged Structure、Outline 和高置信可见样式标题；扫描件、加密 PDF 和明显复杂多栏受控失败。
- 已实现标准化范围/树/摘要校验、源对象读回完整性复核、顺序/并发幂等、失败精确对象清理和孤儿对象安全回收。
- N2.3 专项 11/11，完整回归 73/73 通过。
- N2.3 没有调用模型、生成 Embedding/检索卡、写 Elasticsearch、切换 `activeVersionId`、产生 `READY` 或开启生产 Listener。
- 用户已于 2026-08-06 明确确认“N2.3 完成”；该确认只关闭 N2.3，不自动授权 N2.4。

### 5.7 N2.4 已实现、验证并经用户验收

- Chat 已选 DeepSeek 官方 `deepseek-v4-flash`；检索卡生成使用非思考模式、JSON Output 和 DocQuery 本地严格校验。
- Embedding 已选阿里云百炼华北 2（北京）`qwen3.7-text-embedding`，固定 2,560 维；用户已创建百炼 API Key，但密钥不进入文档或仓库。
- 每个版本固定一张 DocumentProfile，每个非根真实标题一张 RetrievalNode；沿真实标题树自底向上生成，不创建模型目录。
- 单一 `retrieval.jsonl` 保存可读语义卡和 `FLOAT32_LE_BASE64` 导航向量；MySQL 只保存 artifact 清单。
- 已确认生成预算、有限重试、稳定错误码、完整校验和幂等清理规则，详见 [`N2.4 检索卡与导航向量`](../development/N2.4-检索卡与导航向量.md)。
- `pom.xml` 已固定 `langchain4j-open-ai` 1.16.2；已实现供应商无关 Gateway、DeepSeek/百炼 Adapter、底向上生成器、稳定导航文本、2,560 维向量编码和完整读回校验。
- V7 已新增 `document_retrieval_artifact`；已实现 S3 兼容 retrieval 对象端口、配置指纹、顺序/并发幂等、失败精确清理和 fail-closed 孤儿回收。
- 默认测试使用 Fake Gateway/本地 Stub。N2.4 新增 10 项默认回归测试并全部通过；默认完整回归为 83 项执行通过、1 项真实供应商冒烟按设计跳过，另行显式执行的真实供应商冒烟为 1/1 通过。
- N2.4 Service 不改变 DocumentVersion、ProcessingJob 或 activeVersion；没有接管生产 Listener。
- 用户已于 2026-08-10 明确确认“N2.4 完成”；该确认不授权实施 N2.5。

### 5.8 N2.5 已实现、验证并经用户验收

- Elasticsearch Server/Java Client 固定为 9.4.4；已实现 strict Evidence 原文 BM25 索引和 Navigation 2,560 维 cosine 向量索引，写入与后续查询使用稳定别名。
- V8 新增 `document_search_projection` 投影验收单；投影指纹、artifact/Embedding 血缘、Cluster/Index UUID 和精确数量共同防止错误复用。
- 已实现按版本范围清理、确定性 ID Bulk、重复投影恢复、投影完整性复核和 MySQL 原子激活；ES 不保存可变 active 标志，`Document.activeVersionId` 是唯一可见性事实。
- 完整 `DocumentIngestionProcessor` 已接通 RabbitMQ Listener：canonical → retrieval → 双索引 → 验收单 → Version READY / activeVersion / Job SUCCEEDED。
- `DocumentSearchProjectionIT` 3/3 通过，包含真实 MySQL、SeaweedFS、RabbitMQ、Elasticsearch + Fake Gateway 的消息端到端链路、BM25 和真实 KNN；默认回归不调用真实模型。
- 用户已于 2026-08-11 明确确认“N2.5 完成”；该确认只关闭 N2，不自动授权实施 N3。

### 5.9 N3.1 已实现、验证并经用户验收

- 用户已确认 N3 总体设计，并于 2026-08-11 明确说“确认开始 N3”；按冻结门禁，该授权只启动 N3.1。
- 已实现服务请求内部前置边界：解析 Bearer Application Credential、重新校验 Credential/Application/Tenant、隐藏式校验目标 KnowledgeBase 和 READ Grant。
- 已用一条 MySQL 一致性查询固定当前全部 READY `activeVersionId`，并形成不可变版本条目和确定性快照指纹。
- 已实现 Redis Hash + Lua 的原子 `RUNNING/SUCCEEDED` 幂等状态、owner token、续租、失败释放、成功重放、请求/快照冲突和资源上限；Redis 默认关闭且不可用时 fail-closed。
- N3.1 专项使用真实 MySQL 8.4.10 与 Redis 8.8.1，6/6 集成测试和 2/2 fail-closed 单元测试通过；当前全量回归 100 项中 99 项通过、1 项按设计跳过。
- N3.1 没有注册 `/retrieve` 或 `/answer`，没有查询 Elasticsearch、读取 canonical、执行 RRF/Agent，也没有新增数据库迁移或审计表。
- 用户已于 2026-08-11 明确确认“N3.1 完成”；该确认只关闭 N3.1，不授权实施 N3.2。

### 5.10 N3.2 已实现、验证并经用户验收

- 用户已完成 N3.2 产品范围、契约、参数、失败处理和验收点讨论，并于 2026-08-11 明确说“确认开始 N3.2”。
- 已开放 `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/retrieve`，支持 `KEYWORD`、`SEMANTIC`、`HYBRID` 和 `topK=1—20`。
- 已实现独立 Query Embedding、Evidence BM25、Navigation KNN、Java RRF、同一 activeVersion 快照前置过滤、canonical 原文读取与结构化引用。
- HYBRID 的语义支路失败时明确降级为关键词；显式 SEMANTIC、ES 或 canonical 失败返回稳定依赖错误，不伪装成空结果。
- `RetrieveServiceImplTest` 3/3 与真实 MySQL、SeaweedFS、Elasticsearch、Redis + Fake Query Embedding 的 `RetrieveIT` 4/4 通过；默认回归 107 项中 106 项通过、1 项按设计跳过。
- 数据库仍为 V1—V8；未新增 `/answer`、Agent、审计、评测、迁移或 Compose 服务。
- 用户已于 2026-08-11 明确确认“N3.2 完成”，阶段门禁已经关闭；该确认不授权实施 N3.3。

### 5.11 N3.3 已实现、验证并经用户验收

- 用户已冻结 N3.3 的 `/answer` 契约、四个只读工具、权限/版本边界、Agent 预算、失败语义和验收点，并于 2026-08-11 明确说“确认开始 N3.3”。
- 已开放 `POST /api/v1/service/knowledge-bases/{knowledgeBaseId}/answer`；首次按原问题执行一次内部 N3.2 检索，不创建第二个 `RETRIEVE` 幂等任务。
- LangChain4j 只适配 DeepSeek Chat/Tool Calling；Java 固定 QueryAccessContext、activeVersion 快照、工具白名单、循环与成本预算、Redis 续租、Evidence 注册和最终引用校验。
- 已实现 `searchDocuments`、`getDocumentOutline`、`searchWithinDocument`、`readDocument`；模型不能提交 Tenant、KnowledgeBase、Version、Credential、Bucket 或对象 Key。
- `ANSWERED` 必须逐段引用本次登记的 canonical Evidence；`INSUFFICIENT_EVIDENCE` 是 HTTP 200 成功终态。模型、结构、预算和依赖异常均 fail-closed，并且不破坏独立 `/retrieve`。
- `AnswerServiceImplTest` 7/7、供应商本地协议测试 1/1、真实四容器 `RetrieveIT` 7/7 通过；默认全量回归 118 项中 116 项通过、2 项真实供应商冒烟按设计跳过。
- 数据库仍为 V1—V8；未修改 `pom.xml`、迁移或 Compose，未实现多轮聊天、流式回答、通用 Agent、审计和评测。
- 用户已于 2026-08-11 明确确认“N3.3 完成”，阶段门禁已经关闭；该确认不授权实施 N3.4。

### 5.12 N3.4 已实现、验证并经用户验收

- 用户已确认 N3.4 产品范围，并于 2026-08-11 明确说“确认开始 N3.4”。
- V9 已新增最小化 `application_query_audit`；可信应用的成功、拒绝、依赖失败、幂等冲突、降级与重放均有独立记录，无效 Credential 不产生伪造主体审计。
- `/retrieve`、`/answer` 已采用 fail-closed 审计：成功响应必须在终态审计持久化后返回；管理面支持租户隔离的审计列表和详情查询，不向 Application Credential 开放。
- 已生成 `n3-eval-v1`：12 份完全虚构的 PDF/DOCX/TXT/Markdown 语料、40 个固定 case、文件清单和 SHA-256；生产解析器与锚点校验通过，6 份分页文档共 13 页已完成可视检查。用户复核后将其定性为格式、链路和指标冒烟夹具，不作为正式质量 Gold Set。
- 已提供正式 HTTP 评测器和性能脚本；确定性指标公式测试 4/4 通过，Fake Gateway 性能快照覆盖 Retrieve/Answer 的 OWNER/REPLAY 共 60/60 成功并形成 60 条审计。
- 默认全量回归为 30 个套件、124 项：122 项通过、2 项真实供应商冒烟按设计跳过、0 failure、0 error；Failsafe 84/84、`RetrieveIT` 9/9 通过。
- N3.4 当时将正式公开数据集与真实供应商效果评测延期到发布前最终门禁；此后 N5.1 已完成 MMLongBench-Doc 输入适配、DeepDoc 生产接入和 P2 租户完整入库，N5.2-R1 也已完成固定 10 题的真实 Retrieve PILOT。该证据仍不能外推为剩余 54 题、Answer、成本或正式性能结论。
- 用户已于 2026-08-12 明确确认“N3.4 完成”，N3 阶段验收关闭；该确认不授权自动实施 N4。

### 5.13 N4.1 已实现、验证并经用户验收

- 已实现租户管理面的文档列表/详情、版本历史、ProcessingJob 列表/详情和人工重试 6 个 HTTP 路由，响应同时区分 `activeVersion` 与 `latestVersion`，且不泄露对象位置、摘要、Lease 或幂等内部字段。
- 管理接口只允许当前租户 `TENANT_ADMIN`；`PLATFORM_ADMIN`、Application Credential 和跨租户 Session 均不能访问。
- V10 为最终失败持久化 `failure_retryable`，并以 MySQL 唯一事实实现人工重试幂等；人工重试复用同一 `DocumentVersion`，创建递增 `ProcessingJob.attemptNo` 和新 Outbox，不提前切换旧 `activeVersionId`。
- 不可重试失败、旧 attempt、非 latestVersion、活跃 attempt、停用知识库及非 ACTIVE 文档均 fail-closed 拒绝；相同 Key 重放返回同一 attempt，不同命令复用 Key 返回稳定冲突。
- N4.1 相关 4 个集成套件 20/20 通过；真实 MySQL、SeaweedFS、RabbitMQ、Elasticsearch + Fake Gateway 已验证“失败 → 人工重试 → READY”及旧版本持续服务。
- 用户已于 2026-08-12 明确确认“N4.1 完成”，本阶段验收关闭；该确认不授权自动实施 N4.2。

### 5.14 N4.2 已实现、验证并经用户验收

- 已实现整个逻辑 Document 的不可恢复删除；首次受理在 MySQL 事务内完成 `ACTIVE → DELETING`、清空 `activeVersionId`、创建 `DocumentDeletionJob` 和 `DOCUMENT_DELETE_REQUESTED` Outbox，新查询快照立即不可再包含该文档。
- 已实现独立 RabbitMQ 删除主队列、5/30/300 秒重试队列、DLQ、Job 租约和人工重试；删除失败时 Document 始终保持 `DELETING`，不会恢复检索可见性。
- 删除 Processor 清理全部版本的 Elasticsearch Evidence/Navigation 投影、canonical/retrieval 派生对象和原文件；全部成功后才写入 `contentDeletedAt`、Job `SUCCEEDED` 和 Document `DELETED`，并释放文档名称。
- Document/Version/ProcessingJob/DeletionJob/Outbox/查询审计墓碑历史保留；N4.2 本身不支持单版本删除、恢复、撤销删除或管理后台前端，核心管理后台由后续 N4.3 交付。
- N4.2 直接新增 12 项回归；真实 MySQL、SeaweedFS、RabbitMQ、Elasticsearch + Fake Gateway 已完成两版本 `READY → 删除 → DELETED` 端到端验证。默认完整回归为 142 项中 140 项执行通过、2 项按设计跳过、0 failure、0 error。
- 用户已于 2026-08-12 明确确认“N4.2 完成”，本阶段验收关闭；该确认不授权自动开始后续阶段。

### 5.15 N4.3 已实现、验证并经用户验收

- 已实现同源 `/admin/` 管理后台；匿名用户进入登录页，身份以 `/auth/me` 为唯一事实来源，登录后重新获取 CSRF，沿用后端 Session、角色和租户隔离。
- `PLATFORM_ADMIN` 可完成 Tenant 列表、创建、详情、名称修改和启停；`TENANT_ADMIN` 可完成自身租户的 Application、Credential、Grant 和 KnowledgeBase 核心管理。
- 完整 Credential 只在创建后的局部一次性弹窗中出现，关闭即清除，不进入 URL、Web Storage、日志或 TanStack Query Cache；租户初始管理员密码同样不进入查询缓存。
- React + TypeScript + Vite 构建产物已由 Maven 打入同一 Spring Boot JAR；生产仍为一个应用进程，不需要独立 Node 服务。
- N4.3 未新增数据库迁移，未修改 Compose，也未改变 `/retrieve`、`/answer` 或既有管理 API 契约；文档生命周期页面、概览、审计页面和 N5 评测不在本阶段范围。
- 当前默认回归、前端单测和真实浏览器主流程均已通过；用户已于 2026-08-15 明确确认“N4.3 完成”，本阶段验收关闭。该确认不授权自动开始后续阶段。

### 5.16 N4.4 已完成并经用户验收

- 已补齐管理后台的文档上传、Document/Version/ProcessingJob 生命周期、人工重试、逻辑删除和删除重试页面；状态始终来自后端事实。
- 已提供真实 Application Credential + KnowledgeBase Grant 的 Retrieve/Answer 调试页、最小化查询审计页和统一“使用说明”页；Application、KnowledgeBase 与 Document ID 均可复制。
- 使用说明集中展示传统业务系统接入步骤、请求 Header、curl/Java 示例、错误处理，并可下载随 JAR 交付且通过解析校验的 OpenAPI YAML 与 Postman Collection。
- 平台管理员和租户管理员均可阅读使用说明；平台管理员可在 Tenant 详情列出、新增、启停和重置该租户管理员密码，最后一个有效管理员不可停用，状态或密码变化会使该账号旧 Session 失效；不提供公开注册、邮件邀请、删除管理员或自定义角色。
- 临时 MySQL、SeaweedFS、RabbitMQ、Elasticsearch、Redis 和 Fake Gateway 上的真实浏览器链路已覆盖 `READY`、HYBRID Retrieve、带引用 Answer、查询审计、新版本切换和删除至 `DELETED`；临时容器已清理。
- 默认回归为 148 项 Java 测试中 146 项执行通过、2 项真实供应商冒烟按设计跳过，前端 Vitest 13/13，0 failure、0 error；租户管理员补充流程也已通过真实浏览器验证；本阶段未新增迁移，未修改 `pom.xml` 或 `compose.yaml`。
- 用户已于 2026-08-15 明确确认“N4.4 完成”，本阶段验收关闭；正式公开数据集效果评测和多轮 Agent 仍未执行或实现，不得自动进入。

## 6. 已确认的检索方案与后续链路

第一版不采用“固定长度 Chunk + Overlap + 所有 Chunk 向量化 + Top-K Chunk 直接回答”的传统 RAG 主链路。

已冻结的流程是：

```text
上传原文件
→ S3兼容对象存储保存原文件（默认SeaweedFS）
→ MySQL记录Document、Version、Job和Outbox
→ RabbitMQ异步触发处理
→ 解析标准化原文、真实标题树和位置
→ 模型生成DocumentProfile与真实章节RetrievalNode检索卡
→ 保存单一retrieval.jsonl检索卡与导航向量产物
→ Elasticsearch建立原文BM25索引和检索卡向量索引
→ 校验投影完整后将版本切换为READY

用户查询
→ 校验Application Credential和KnowledgeBase Grant
→ 固定同一次请求使用的activeVersion快照
→ Redis处理Idempotency-Key和短期结果复用
→ ES原文关键词检索与检索卡向量导航并行
→ Java使用RRF融合排名
→ Retrieve读取并返回可追溯原文证据
→ Answer由受控Agent继续调用只读工具，读取原文后生成带引用的单轮答案
```

关键边界：

- 只使用文件已有的真实标题，不生成虚拟子目录。
- 根节点由唯一 DocumentProfile 表达；每个非根真实标题对应一个 RetrievalNode。没有子标题的长章节第一版仍是一个 RetrievalNode；关键词索引使用页、段落或行位置定位原文。
- 检索卡使用 DeepSeek 官方 `deepseek-v4-flash` 生成，导航向量使用阿里云百炼 `qwen3.7-text-embedding` 生成，固定 2,560 维。
- 检索卡和导航向量已保存为单一不可变 `retrieval.jsonl`，并由 MySQL 清单、生成指纹和多层摘要保护。
- 向量只负责文档和章节级语义导航，检索卡不能作为最终证据。
- 关键词检索负责在原始正文中精确定位；最终答案必须读取原文。
- Elasticsearch 同时承担 BM25 和 `dense_vector` 导航索引，第一版不增加独立向量数据库。
- LangChain4j 只使用底层 Chat、Embedding 和 Tool Calling 适配；权限、RRF、Agent 循环、预算和审计由 DocQuery 控制。
- RabbitMQ Consumer 第一版仍在同一 Spring Boot 应用内。
- MySQL Transactional Outbox 解决数据库提交与消息发布之间的可靠衔接；消息按至少一次投递处理，消费者必须幂等。
- Redis 只用于 `/retrieve`、`/answer` 的请求幂等和短期结果复用，不缓存 Credential、Grant、activeVersion，不负责 RabbitMQ 消费幂等，也不保存管理员 Session。
- 当前生产默认通过 DeepDoc HTTP 支持 PDF、DOCX、TXT 和 Markdown，并已验证复杂文本型 PDF 的版面恢复；仍不承诺扫描件 OCR 成功率、图片理解、表格网格或公式语义。
- 对外提供独立 `Retrieve` 和受控单轮 `Answer`；第一版不做多轮聊天、通用 Agent 或流式回答承诺。

## 7. 当前明确没有实现或没有执行的能力

- 管理后台租户概览大屏和管理操作审计；N4.4 已补齐文档生命周期、服务 API 调试、查询审计和使用说明页面。
- 管理操作审计；N3.4 只有服务面查询审计。
- MMLongBench-Doc 的 100 份真实 PDF/80 问题输入已完成适配和 P2 真实租户完整入库：100/100 READY、80/80 case 保留。该结果证明当前 300 页边界内真实 PDF 的完整入库可行性，但不外推到扫描件 OCR、图片理解、复杂表格语义或四格式总体质量。N5.2-R3 已完成 80 题 Retrieve 有效集评测并待用户验收；Answer 只完成固定 5 题失败样本诊断，尚无合格的正式质量结论。
- N5 路线图中的系统化故障注入、正式并发压测和可复现工程报告尚未执行；现有证据是默认回归、真实依赖集成测试、真实浏览器链路和 N3.4 Fake Gateway 确定性基线，不能替代正式 N5 报告。
- 多轮 Conversation 数据模型、会话历史/摘要、流式回答和面向多轮记忆的 Agent 尚未设计或实现；现有 `/answer` 是受控单轮 Answer。

N3.1—N3.4、N4.1—N4.4、N5.1-P0/P1/P2 与 N5.2-R1 均已实现或执行、验证并经用户验收。N5.1 已把 MMLongBench-Doc 锁定并真实入库为 100 份 READY PDF 和 80 个冻结问题；N5.2-R3 的 80 题 Retrieve 有效集已执行并待验收。N5.3 Answer 仍停留在 5 题定向诊断，没有形成可扩大执行的合格结论。

2026-08-24 全部 DeepSeek Chat 调用已替换为 PackyAPI Responses：实际生效 Base URL 为
`https://slb-v1.api.fan/v1`，实际模型 ID 为 `grok-4.6`。用户口述的 `grok-5.6` 不在当前
模型列表中，因此不得把本阶段证据外推到该名称。检索卡与 Answer 源码、配置和新产物元数据
已切换，百炼 Embedding 不变，旧 `DEEPSEEK / DISABLED` 检索产物保持只读兼容。真实最小
Answer 冒烟已完成一次 Tool Calling 和 Tool Result 续接，并返回严格最终 JSON；Java 70 项
单测（68 通过、2 个真实供应商冒烟按设计跳过）、检索产物 IT 4/4 和前端 Vitest 14/14
通过。新版后端已在 8080 启动；没有自动重建现有 100 份文档。本证据只说明协议链路可用，
不代表缓存、成本、正式延迟或 Answer 质量达标。详见
[`N5.3-A5 PackyAPI 模型供应商替换`](../development/N5.3-A5-PackyAPI-模型供应商替换.md)。

2026-08-24 已使用 PackyAPI Responses `grok-4.6` 完成 N5.3-A6 固定 5 题 Answer 定向复验：
HTTP 成功 5/5、供应商异常 0，但仅 2/5 返回回答，自动与人工正确均为 1/5，正确文档引用
2/5、任意 Gold 页引用 1/5、平均 Gold 页覆盖率 0.20。失败主因已收敛为 Agent 提前拒答及
同名章节消歧/完整性判断不足，不是 PackyAPI 协议或 DeepDoc 整体不可用。A6 状态为
`COMPLETED_FAILED_REVALIDATION / PENDING_USER_ACCEPTANCE`，不得自动执行 20 题 Answer
PILOT。详见 [`N5.3-A6 Answer 定向复验`](../development/N5.3-A6-Answer定向复验.md)。

同日完成 N5.3-A7 Grok Agent 提示词与 Responses 状态续接实验。协议冒烟证明 PackyAPI
支持 Function Calling、加密 reasoning 无状态续接和 Prompt Cache；修正完整上下文续接后，固定
5 题仍只有 4/5 逻辑请求成功、1/5 返回回答、人工正确 0/5，且 `0937` 从 A6 正确回退为拒答。
因此 A7 状态为 `COMPLETED_FAILED_REVALIDATION / PENDING_USER_ACCEPTANCE`，实验运行代码已
回滚到 A6 `answer-agent-v4 / answer-policy-v4`，不得自动扩大评测。详见
[`N5.3-A7 Grok Agent 提示词与 Responses 状态续接`](../development/N5.3-A7-Grok-Agent提示词与Responses状态续接.md)。

## 8. N2 分阶段顺序

| 子阶段 | 范围 | 本阶段不做 |
| --- | --- | --- |
| N2.1 | Document、DocumentVersion、ProcessingJob、OutboxEvent 数据模型和管理面上传受理契约 | 不接 MQ、不解析、不建 ES 索引 |
| N2.2 | SeaweedFS 原文件保存、Outbox Publisher、RabbitMQ 发布消费、重试和死信骨架 | 不生成检索卡、不切换 READY |
| N2.3 | PDF、DOCX、TXT、Markdown 解析，生成标准化原文、真实标题树和位置映射 | 不生成虚拟目录、不调用模型 |
| N2.4 | LangChain4j Gateway、DocumentProfile/RetrievalNode 生成和导航 Embedding | 不实现查询 Agent |
| N2.5 | ES 关键词与导航向量双索引、投影校验和 activeVersion 原子切换 | 不实现对外 Retrieve/Answer |

N3 承担服务面鉴权、Redis 幂等、双路检索、RRF、原文证据、受控 Agent、查询审计和评测执行能力；N3.1—N3.4 均已验收，N3 阶段已经关闭。正式公开数据集效果评测保留为发布前最终门禁。

## 9. 新会话的第一项工作

新会话完成规定文档阅读后，先核对 N3、N4.1—N4.4、N5.1-P0/P1/P2 与 N5.2-R1 均已关闭，100 份租户完整入库已完成，N5.2-R3 的 80 题 Retrieve 有效集已执行并待验收；N5.3-A7 固定 5 题 Answer 复验失败并已回滚，多轮 Conversation 尚未开始。当前：

- N3.1 已实现、验证并经用户确认完成。
- N3.2 已实现、验证并经用户确认完成。
- N3.3 已实现、验证并经用户确认完成。
- N3.4 已实现、验证并经用户确认完成，N3 阶段已经关闭。
- N4.1 已按确认设计实现、验证并经用户确认完成。
- N4.2 已按确认设计实现、验证并经用户确认完成。
- N4.3 已按确认设计实现、验证并经用户确认完成。
- N4.4 原冻结范围和租户管理员管理补充均已实现、验证并经用户验收，阶段已关闭。
- N5.1 已实现 `mmlongbench-docquery-v1` 候选：锁定 revision 的 100 份上游真实 PDF、64 个纯文本证据可回答问题、16 个不可回答问题、来源指纹、确定性选择器、manifest、case 和 checksum。
- P1 前的本地 PDF Parser 准入仅 8/100 成功；P2 接入 DeepDoc 并完成真实租户完整入库后，最终 100/100 READY、80/80 case 保留，Canonical/Retrieval/Projection 各 100。
- 用户已于 2026-08-16 明确确认 N5.1-P0 完成并关闭。固定 RAGFlow `v0.26.4` 在断网、4 CPU、12 GiB、无 GPU 设备请求的单容器中，将原固定顺序前 2 份复杂 PDF 均恢复为带页位置的非空块，124/124 页有文本块，7/7 官方证据页覆盖。两份 CPU 耗时分别为 701.914 秒和 377.729 秒。
- P0 原计划 10 份，但用户在第 1 份完成后明确缩减为 2 份；第 2 份结果落盘后主动停止，后续 8 份未执行。CPU 首轮的退出码 137 来自主动停止，容器状态为 `OOMKilled=false`、`RestartCount=0`；在该首轮收口时 GPU 尚只完成 provider 探针。
- 用户随后明确要求对相同两份文档执行 GPU 对照。固定派生镜像 `docquery/deepdoc-gpu:ragflow-v0.26.4-torch-2.13.0-cu126` 的版面、表格、OCR 检测和 OCR 识别 session 均实际包含 CUDA provider；两份 GPU 耗时分别为 108.475 秒和 49.688 秒，总体加速 6.826 倍，容器退出码 0、`OOMKilled=false`。CPU/GPU 块数和字符数略有差异，不能声称正文逐字节一致。
- DeepDoc P0 CPU/GPU 技术验证任务仍作为默认关闭的独立 profiles 保留在根 `compose.yaml`；生产 `deepdoc` GPU HTTP 服务已改为默认 Compose 服务，方便从 Docker Desktop 启动整个 `docquery-greenfield` 分组。runner 与固定 GPU Dockerfile 位于 `tools/document-parser-p0/`，报告位于 `evaluation/mmlongbench-docquery-v1/reports/`。
- 用户已明确授权 N5.1-P1，并补充要求四格式全部替换且默认开启。生产 Java 配置默认启用统一 DeepDoc Adapter，PDF、DOCX、TXT、Markdown 全部走同一 HTTP 服务，显式设置 `DOCQUERY_DEEPDOC_ENABLED=false` 才回退四个本地 Adapter。P1 验收时生产 DeepDoc 使用显式 Compose profile；用户于 2026-08-17 又要求将其改为默认服务，并删除已创建的 P0 GPU 技术验证容器，方便 Docker Desktop 一键启动整个分组。DeepDoc 内部解析并发固定为 1；继续使用既有文档处理主队列和整链路 Consumer，不新增解析队列或后处理队列。多个 Listener 可以并行持有不同文档，只有进入 DeepDoc 的解析段串行等待，之后仍各自继续检索卡、Embedding、索引和激活。
- P1 最终默认回归为 Java 155 项中 153 项通过、2 项跳过，前端 13/13；HTTP 服务并发测试证明两个同时请求均正常完成且最大解析并发为 1。最终容器健康并实际使用 CUDA；真实 GPU PDF 冒烟只执行既有 49 页样本一份，得到 548 个带页位置的中立块，约耗时 73—75 秒，另各用 1 份既有 DOCX、TXT、Markdown 冒烟夹具验证格式分发和位置。
- P2 已调用真实 DeepSeek 与百炼完成入库，但入库 token 未统一持久化、费用未计算。N5.2-R1 已使用专用应用凭证完成固定 10 题、3 模式、30 个真实 `/retrieve` 请求并由用户验收；N5.2-R3 已完成 80/80 个 HYBRID 请求，Doc Hit@5=`0.9250`、Page Coverage@10=`0.6554`、MRR@10=`0.8585`，当前待用户验收。
- R1 三模式结果分别为：KEYWORD Doc R@5=`0.9000`、Page Coverage@10=`0.7833`、MRR@10=`0.7458`；SEMANTIC 为 `0.9000`、`0.6833`、`0.8667`；HYBRID 为 `1.0000`、`0.8333`、`0.9333`。PILOT 每种模式只有 10 个延迟样本，nearest-rank P95 等于最大值，不能作为正式性能结论。
- 用户于 2026-08-20 明确确认 N5.2-R1 完成；此后已授权并执行 N5.2-R2/R3，其中 R3 结果待验收。N5.3-A7 失败后不得自动继续 20/80 题 Answer 评测。
- `mmlongbench-docquery-v1` 不是官方完整榜单结果；当前只能作为真实复杂 PDF 可靠性压力集候选。P0 两份成功、P1 单份真实 PDF HTTP 冒烟和三种非 PDF 夹具冒烟不代表生产 DocQuery 已验证扫描件 OCR、100 份总体准入或四格式总体质量。
- 用户希望项目保持适合秋招展示的规模；N5 后续仍坚持最小必要证据，不扩大为大规模自制 Gold Set。
- 多轮 Agent 排在外部 API 和正式效果评测之后；N4.4 已完成外部 API 交付，但多轮范围仍需在 N5 之后单独讨论和授权。
- 用户已明确确认 N5.1-P1 完成，本阶段关闭。
- 用户于 2026-08-17 确认 P2 改为复用既有“测试科技”租户：创建隔离的 N5 知识库，将 100 份原始 PDF 通过正常上传、RabbitMQ、DeepDoc、真实检索卡与 Embedding、双索引和激活完整入库，再根据 READY 与证据页结果冻结 N5.2 case；后续 N5.2 使用该租户的专用应用凭证调用 `/retrieve`、`/answer`。
- P2 已使用“测试科技”租户下的“评测应用”和“评测知识库”完成 100 份真实完整入库，两者通过 READ Grant 关联；用户已于 2026-08-17 明确确认 N5.1-P2 完成。该确认不允许自动开始 N5.2。

## 10. 新会话启动语

将下面内容复制到新会话：

```text
这是 DocQuery 项目的新会话，工作区是 D:\IdeaProjects\DocQuery。

请先完整阅读 docs/product/00-DocQuery-新服务交接说明.md，再严格按照其中顺序完整阅读产品文档和独立技术选型。不要从旧对话印象或旧工程代码直接推断。

当前 N0—N4.4、N5.1-P0/P1/P2 与 N5.2-R1 均已实现或执行、验证并经用户验收并关闭；N5.2-R3 已执行并待验收。生产默认由统一 DeepDoc Adapter 接管 PDF、DOCX、TXT、Markdown；PDF 最大 300 页、页批次 1、内部解析并发 1。当前免费回归为 Java 180 项中 178 项通过、2 项真实供应商冒烟按设计跳过，0 failure、0 error；前端 Vitest 14/14，DeepDoc 10/10。外部 `/retrieve`、`/answer`、OpenAPI、Postman 和后台使用说明已经交付。

`n3-eval-v1` 只作为格式、链路和指标冒烟夹具，不是正式 Gold Set。N5.1 已将 MMLongBench-Doc 锁定为 `mmlongbench-docquery-v1`：100 份上游真实 PDF、64 个纯文本证据可回答问题和 16 个不可回答问题。P2 已在“测试科技”租户完成 100/100 READY，Evidence 78,062/78,062、Navigation 2,422/2,422，80/80 case 已冻结；扫描件 OCR 和四格式总体质量仍未验证。

P2 已调用真实 DeepSeek 与百炼完成检索卡和导航 Embedding，但入库 token 未统一采集、费用未计算。N5.2-R3 已对 80 道有效题执行 HYBRID 评测：80/80 成功、Doc Hit@5=`0.9250`、Page Coverage@10=`0.6554`、MRR@10=`0.8585`，结果待用户验收。N5.3-A7 对 5 道失败题验证 PackyAPI `grok-4.6` 的提示词和 Responses 状态续接，人工正确仍为 0/5，实验代码已回滚到 A6 基线。多轮 Conversation、会话记忆和流式 Answer 尚未设计或实现。下一步只讨论单轮 Answer Agent 的最小任务完成约束；在独立明确授权前，不扩大付费评测或开始多轮 Agent。
```
