# DocQuery Java 后端项目面试 100 问答

> 本文基于当前源码、配置和最终评测报告整理，回答采用面试现场的第一人称口吻。当前真实边界是：只支持单轮 Answer，尚未实现多轮 Conversation、跨请求记忆和流式输出；尚未完成正式并发压测；96.25% 只对应筛选后的常规文本问题。

## 1. 请用一分钟介绍一下 DocQuery 项目。

**回答：** DocQuery 是我负责开发的一套面向传统业务系统后端的可追溯文档检索与单轮问答服务，不是简单的“上传 PDF 后调用大模型”。在入库侧，文件先进入 SeaweedFS，再通过 MySQL Transactional Outbox 和 RabbitMQ 异步触发 DeepDoc 解析、检索卡生成、Embedding 和 Elasticsearch 双索引投影，全部验收成功后才原子切换生效版本。在查询侧，系统先校验应用凭证和知识库授权，再固定 activeVersion 快照，通过 Redis Lua 做请求幂等，使用 BM25、KNN 和 RRF 完成混合检索；Answer 再通过受控 Agent 选择原文证据并生成带引用答案。最终完成了 100 份真实 PDF 全链路入库，Retrieve 的 80 题评测全部请求成功、Top5 文档命中率 92.5%，常规文本 Answer 评测人工正确 77/80。

## 2. 这个项目解决了什么业务问题？

**回答：** 我想解决的是传统客服、工单、运维等业务系统重复建设文档解析和检索能力的问题。业务系统仍然管理自己的终端用户和业务权限，DocQuery 只提供应用级鉴权、文档生命周期、检索和单轮问答。这样接入方不需要理解对象存储、消息队列、Embedding 或 Elasticsearch，只需要配置应用凭证和知识库 ID，就能获得可追溯的原文证据。

我刻意把产品边界放在“后端能力服务”而不是完整客服产品：DocQuery 管理客户、应用、知识库和文档授权，但不接管接入方的用户体系，也没有实现多轮 Conversation。它重点解决文档版本更新时不中断查询、异步处理失败可恢复、检索结果能回到具体版本和页码，以及模型只能使用授权证据这些后端问题。

## 3. 你在项目中具体负责了哪些模块？

**回答：** 这是个人项目，我负责了从产品边界、数据模型、后端实现到测试和公开数据集评测的完整过程。核心工作包括 Spring Security 管理端认证、Application Credential 与 Grant 授权、文档版本状态机、对象存储抽象、Outbox 与 RabbitMQ、DeepDoc 接入、Elasticsearch 双索引、Redis 查询幂等、LangChain4j Agent、引用校验以及管理后台和评测脚本。

如果按主线概括，我负责两条链路：入库链路从 multipart 上传一直到 READY 激活，查询链路从应用鉴权一直到 Retrieve 或带 Citation 的 Answer。我不仅实现成功路径，也为对象写入后事务失败、消息重复、Worker 宕机、ES 半投影、凭据撤销和模型输出非法引用设计了恢复或拒绝逻辑。最终评测脚本和人工复核报告也由我整理，因此简历数字能够对应到具体版本和范围。

## 4. 这个项目最有技术含量的三个点是什么？

**回答：** 第一是可靠异步入库：用 Outbox、Publisher Confirm、消费租约、分级重试和稳定 ID 处理跨组件一致性与重复投递。第二是版本一致性：MySQL 是唯一可见性事实，只有 canonical、检索卡和双索引全部验收后才切换 activeVersion，新版本失败不影响旧版本。第三是受控问答：模型只负责工具循环和生成，权限、幂等、预算、Evidence 注册和引用校验都由 Java 控制。

这三个点是相互连接的：可靠消息保证候选版本最终有机会被处理，验收式激活保证半成品永远不可见，受控 Agent 保证进入模型的仍是本次授权快照中的原文证据。我的设计重点不是堆组件，而是为每个组件规定失败后谁能重试、谁能改变事实、谁只能做投影，从而让跨系统链路可解释。

## 5. 请画出项目的整体架构和核心数据流。

**回答：** 管理面是 React 管理后台加 Spring Boot 管理 API，管理员使用 Session。服务面由传统业务后端携带 Application Credential 调用 `/retrieve` 或 `/answer`。入库链路是 SeaweedFS 保存原文件，MySQL 保存 Document、Version、Job 和 Outbox，RabbitMQ 触发 DeepDoc、检索卡、Embedding 和 Elasticsearch 投影，最后事务激活版本。查询链路是鉴权与版本快照、Redis 幂等、Elasticsearch BM25/KNN、Java RRF、canonical 原文读取；Answer 在此基础上增加 Rerank、Agent 工具循环和 Final Answer Pass。

从分层看，Controller 只做协议转换，Application Service 编排用例，领域状态和 Mapper 控制事务事实，基础设施 Adapter 对接 S3、RabbitMQ、Redis、ES 和模型。所有外部组件都围绕 MySQL 中的 tenant、knowledgeBase、documentVersion 和 job 事实工作：RabbitMQ 传任务，ES 提供可重建查询投影，Redis保存短期执行权，对象存储保存内容，但只有激活事务决定某个版本是否能被查询。

## 6. 一份文档从上传到可以检索，完整链路是什么？

**回答：** Controller 接收 multipart 文件后，`DocumentUploadCoordinatorImpl` 先把原文件写入 S3 语义对象存储，同时计算大小和 SHA-256；然后 MySQL 事务创建 DocumentVersion、ProcessingJob 和 OutboxEvent。Publisher 将稳定 ID 消息投递到 RabbitMQ，Consumer 获得 Job 租约后依次执行 `ensureCanonical`、`ensureRetrieval`、`ensureProjection` 和 `activate`。前三步都可幂等重建，最后一步在短事务中校验投影计数、版本状态和租约 owner，再把版本置为 READY、更新 activeVersion 并将 Job 置为成功。

每一步都有明确产物：DeepDoc 解析后生成带 SourcePosition 的 canonical JSONL；检索阶段构建 DocumentProfile 和标题节点卡片并批量生成 2560 维向量；投影阶段写入 Evidence 与 Navigation 双索引并生成收据。任一步失败都不会提前修改 activeVersion，新上传只成为 latestVersion 候选。消息确认、分级重试和 DLQ 负责恢复执行机会，最终可见性仍由 MySQL 一次提交。

## 7. 一次 `/retrieve` 请求的完整执行流程是什么？

**回答：** 首先解析 Bearer Application Credential，校验 Credential、Application、客户、KnowledgeBase 和 READ Grant，然后用一条 MySQL 查询固定当前 READY activeVersion 集合并生成快照指纹。接着用 Redis Lua 竞争 Idempotency-Key 的执行权。OWNER 请求并行执行 Evidence BM25 和 Navigation KNN，Java 按 `1/(rrfK+rank)` 做 RRF，随后从 canonical 对象中读取真实原文并返回引用；REPLAY 请求直接返回短 TTL 内的结果。HYBRID 的语义支路失败时可以明确降级为 KEYWORD，关键词或 canonical 失败则返回依赖错误。

两路 ES 查询都会下推 tenant、knowledgeBase 和 active version 过滤，不能先召回全局结果再在 Java 中删掉越权项。ES 命中只提供地址，Service 还要读回 canonical 并校验 block 与版本归属，最终响应才包含文档、版本、标题和页码。最终 80 题顺序实测全部成功且无降级，DocHit@5 为 92.5%，P95 为 436.145 ms；这不是并发性能结论。

## 8. 一次 `/answer` 请求的完整执行流程是什么？

**回答：** Answer 先完成与 Retrieve 相同的鉴权、版本快照和 Redis OWNER 竞争，然后 Java 用原问题执行一次初始搜索。LangChain4j Agent 只能调用 `search`、`open` 和 `submit_evidence`，其中搜索结果可以经过 qwen3-rerank，工具返回的 canonical 文本由 Java 注册成 E#。Agent 最终只提交选中的 Evidence 和 R# 上下文包，隔离的 Final Answer Pass 只接收原问题与这些完整证据，不接收搜索历史。最后 Java 校验状态和 Evidence ID，转换成文档名、版本、页码引用，再写审计并完成 Redis 结果。

整个 Agent 始终复用请求开始时的 `QueryAccessContext`，模型不能改变客户、知识库或版本范围；D#/S#/E#/R# 也只是当前 Session 的临时能力句柄。`AgentObserver` 限制轮次、模型调用、工具调用、来源预算和总时限，Finalizer 输出还要通过结构化校验。最终常规文本评测人工正确 77/80、P95 约 22.4 秒，但该结果不覆盖 OCR、复杂表格和跨页表格。

## 9. DocQuery 和普通“上传 PDF + RAG 问答”项目有什么区别？

**回答：** 普通 Demo 往往是固定 Chunk、全部向量化、TopK 拼 Prompt，文档更新、失败恢复和权限边界比较弱。DocQuery 把导航、定位和举证分开：向量只搜索 DocumentProfile 和真实章节检索卡，BM25 搜索 canonical 原文，最终答案只能引用 Java 登记过的原文 Evidence。另外项目还实现了应用级授权、版本原子切换、Outbox、重复消费幂等、安全删除和可复现评测，所以重点是后端工程闭环，而不只是模型调用。

具体差异体现在失败场景：上传成功不等于立刻可查，只有全部产物验收后才能 READY；消息可以重复，但阶段必须幂等；新版本失败时旧版本仍服务；模型能看到什么由 Java 鉴权快照和证据注册表决定。换句话说，我把 RAG 当成一个有状态、跨组件的后端业务，而不是一次“检索后调用 Chat API”的脚本。

## 10. 为什么项目强调“可追溯”？

**回答：** 因为检索和回答中的每条 Evidence 都绑定 documentId、documentVersionId、headingNodeId、canonical 范围和 SourcePosition。模型看到的 E# 只是本次请求内的临时引用，外部响应会由 Java 转换成文档名称、版本、标题路径、页码和原文片段。检索卡摘要不能直接成为引用，从而避免模型根据派生摘要回答却无法回到原文。

在企业场景里，“答案看起来合理”不足以支持审计和纠错，调用方需要知道它基于哪一版文档、哪一页原文。版本号还能解释同一问题为什么在更新前后答案不同。当前评测未发现人工确认的无依据 Citation，但我只把它表述为该批 80 题的复核结果，不宣称模型在所有问题上绝不会产生错误引用。

## 11. 如何将答案追溯到具体文档、版本、标题和页码？

**回答：** DeepDoc 解析结果先被标准化为 canonical JSONL，每个 EvidenceBlock 都有稳定 blockId、ordinal、标题归属、canonical offset 和源位置。Retrieve 从 Elasticsearch 得到的只是地址，之后会读回并校验 canonical 对象。Answer 的 `AgentSession` 再把这些块注册成 E#，Final Answer 只能提交本次允许集合中的 E#；`AnswerServiceImpl` 最后从注册表生成 Citation，所以模型不能自行伪造文档或页码。

这条链路可以概括为“解析位置 → canonical 身份 → ES 地址 → Session 句柄 → 外部 Citation”。中间任何一步的 versionId、blockId、摘要或范围不一致都会失败，而不是降级成一段无来源文本。页码仅在源格式和解析结果确实提供时返回，DOCX、TXT、Markdown 使用各自可验证的位置语义，不会为了统一接口伪造 PDF 页码。

## 12. 为什么采用 Spring Boot 单体，而不是微服务？

**回答：** 当前项目是个人项目，核心目标是把身份、入库、检索和问答的边界做完整，而不是展示服务数量。单体可以减少分布式调用、配置中心和部署复杂度，同时 RabbitMQ Consumer 仍然具备异步隔离。如果后续压测证明 DeepDoc 或模型调用明显抢占在线查询资源，我会优先拆文档 Worker；当前没有压测证据，所以不提前微服务化。

逻辑上代码已经通过 Controller、Application Service、领域服务和基础设施端口隔离职责，拆部署并不要求重写业务模型。当前真正跨进程的是 DeepDoc 和外部存储/模型，耗时入库也通过 RabbitMQ 与请求线程解耦。先保持模块化单体可以更容易维护事务边界，等出现独立扩缩容、故障域或团队所有权需求时再拆，避免为了架构名词制造分布式一致性问题。

## 13. RabbitMQ、Redis、Elasticsearch、MySQL 和对象存储分别负责什么？

**回答：** MySQL 保存身份、授权、文档版本、任务、Outbox、activeVersion 和审计，是业务事实源；对象存储保存原文件、canonical 和 retrieval JSONL；RabbitMQ 只负责异步任务传递；Elasticsearch 保存可重建的 Evidence 与 Navigation 查询投影；Redis 只保存 `/retrieve`、`/answer` 的短期幂等状态和成功结果。任何一个投影组件都不能反向改变 MySQL 中的业务事实。

组件故障后的处理也由这个定位决定：RabbitMQ 恢复后重发 Outbox，Redis 丢失时不影响文档状态但查询接口在启用幂等时 fail-closed，ES 丢失时从 artifact 重建，SeaweedFS 中内容则要靠 SHA 和 manifest 校验。只有 MySQL 激活事务能改变在线可见版本，避免多个组件各自保存一份“当前版本”导致冲突。

## 14. 为什么以 MySQL 作为业务事实源？

**回答：** 文档状态、版本可见性和授权都需要事务、行锁和一致性约束，MySQL 更适合承载这些长期事实。Elasticsearch、Redis 和 RabbitMQ 分别是检索投影、临时状态和消息传递，它们都可能丢失或重复。代码中只有 `DocumentVersionActivationServiceImpl` 能修改 activeVersion，并且在同一个数据库事务里更新 Version、Document 和 ProcessingJob，从而把可见性收敛到一个事实源。

数据库迁移还通过唯一索引约束客户内名称、版本号、幂等哈希、每版本 artifact 和每 Job Outbox 等不变量，Service 再用显式锁顺序与条件更新表达状态迁移。项目没有依赖数据库物理外键和 CHECK 来包办所有规则，而是把跨表业务校验放在 Java 与 SQL 条件中；因此测试重点是非法状态组合和并发更新，而不只是 CRUD 是否成功。

## 15. 为什么选择 MyBatis，而不是 JPA 或 MyBatis-Plus？

**回答：** 项目的核心查询经常显式携带 tenantId、knowledgeBaseId、状态和版本范围，我希望 SQL 在代码审查时清楚可见，避免通用 ORM 抽象隐藏过滤条件。MyBatis Mapper 也便于使用 `FOR UPDATE`、`SKIP LOCKED`、条件更新和一条 SQL 固定 activeVersion 快照。代价是 CRUD 和字段映射需要手写，但当前规模下可控，而且关键业务规则仍然放在 Service 中。

例如 Outbox 领取需要跳过被其他 Publisher 锁定的行，激活需要按固定顺序锁定多张事实记录，凭据和 Grant 查询必须把状态过滤写清楚，这些都比自动实体状态同步更适合显式 SQL。MyBatis 并不自动保证安全，我仍要求每个 Mapper 的作用域参数完整，并让更新语句携带旧状态或 owner 条件，通过受影响行数判断竞争结果。

## 16. 为什么选择 RabbitMQ，而不是 Kafka？

**回答：** 这里是耗时工作队列，不是需要长期回放和多个消费组的事件流。RabbitMQ 原生支持手动 ACK、Publisher Confirm、TTL 重试队列和 DLQ，比较适合文档解析、模型调用和索引任务。Kafka 当然也能实现，但会额外引入分区、消费位点和日志保留等运维概念，对当前任务模型没有明显收益。

项目当前 Consumer 默认并发为 1、prefetch 为 1，更强调一条长任务的可靠状态转换而非高吞吐事件流；消息体也只携带稳定任务 ID，业务进度在 MySQL。若未来需要大量事件回放、多下游订阅或按分区维持长期顺序，Kafka 可能更合适。但在没有并发压测和这些需求之前，RabbitMQ 的任务队列语义更直接。

## 17. 为什么选择 Elasticsearch，而不是单独引入向量数据库？

**回答：** 项目同时需要中文全文 BM25 和 2560 维向量 KNN，Elasticsearch 已经可以用 strict mapping、`dense_vector` 和过滤条件完成两路查询。使用同一套系统还能复用 tenant、knowledgeBase 和 activeVersion 前置过滤，减少一个存储组件及其一致性问题。只有未来出现 Elasticsearch 无法满足的向量规模或索引能力需求时，我才会引入独立向量库。

当前双索引把 Evidence 文本和 Navigation 向量分开，Gateway 分别查询后由 Java 做 RRF，既能支持关键词精确匹配，也能支持章节语义导航。ES 仍被视为可重建投影，不能决定版本 READY。选择依据是当前能力覆盖和组件收敛，不是声称 ES 在所有向量规模下都优于专用向量数据库。

## 18. 为什么选择 SeaweedFS，而不是 MinIO、OSS 或本地文件系统？

**回答：** 业务层实际依赖的是 `SourceObjectStore`、`CanonicalArtifactStore` 和 `RetrievalArtifactStore`，首个实现用 AWS SDK v2 访问 S3 兼容接口，SeaweedFS 只是当前本地部署实现。这样项目可以离线复现，又不会把业务模型绑定到具体 Server。企业已有 OSS 时可以增加或替换 Adapter，本地文件系统则不适合后续多实例和对象生命周期管理。

因此我不会把“会使用 SeaweedFS”当作核心亮点，真正的设计点是 S3 语义抽象、流式校验和安全生命周期。对象写入时记录 size/SHA，删除时校验 bucket、前缀和精确键，孤儿清理再与 MySQL manifest 交叉检查。更换存储产品后，这些业务规则仍然成立，只需要替换基础设施实现和部署配置。

## 19. 为什么选择 LangChain4j，而不是 Spring AI？

**回答：** 选择 LangChain4j 主要是为了统一 Chat、Embedding 和 Tool Calling 协议，并使用 `AiServices` 管理单次请求内的工具消息循环。但我没有把权限、检索、预算和引用交给框架，`LangChain4jAnswerAgentAdapter` 只负责模型与工具协议。Spring AI 也能做协议适配，但同时引入两套框架没有收益，项目价值主要仍在 Java 业务控制层。

具体来说，工具能打开哪些内容由 `AgentSession` 决定，预算和 deadline 由 `AgentObserver` 控制，最终 JSON 与 Citation 由 `AnswerServiceImpl` 校验；这些都不依赖 LangChain4j 的默认行为。这样框架升级或替换时，安全边界不会一起丢失。当前使用的 MessageWindowChatMemory 也只服务一次请求内循环，不代表系统已实现跨请求会话记忆。

## 20. 项目中哪些数据是业务事实，哪些数据是可重建投影？

**回答：** Tenant、Application、Credential、Grant、Document、DocumentVersion、Job、Outbox、activeVersion 和审计是 MySQL 事实。原文件与 canonical 是可追溯内容事实，检索卡属于可重建派生产物；Elasticsearch 双索引和 Redis 结果更明确是可丢失投影。即使 ES 或 Redis 丢失，也不应导致错误版本被激活或越权数据变为可见。

恢复顺序也由事实层级决定：源对象和 canonical 提供内容依据，retrieval artifact 可以从 canonical 再构建，ES 可以从两个 artifact 重投，Redis 结果则可以过期后重新执行。ProjectionReceipt 只是证明某次投影通过验收，仍需与当前索引 UUID 和计数匹配。把这些层次分清后，灾难恢复和删除流程才知道哪些数据能重算、哪些墓碑必须保留。

## 21. Document、DocumentVersion 和 ProcessingJob 分别解决什么问题？

**回答：** Document 表示长期逻辑文档，例如“维修手册”；DocumentVersion 表示某次具体上传内容，保存对象引用、版本号和处理状态；ProcessingJob 表示一次处理尝试，保存 attempt、租约、失败码和重试属性。这样内容版本和执行尝试不会混在一起，同一失败版本可以通过新的 Job attempt 重试，同时旧的 activeVersion 继续服务。

如果只用一张 Document 表，上传新文件时就必须覆盖当前路径和状态，失败后很难恢复旧内容；如果把 Job 状态直接写在 Version 上，多次重试的 owner、attempt 和失败原因又会互相覆盖。三者拆分后，Document 管可见性，Version 管不可变内容身份，Job 管一次执行生命周期，分别对应不同并发和审计需求。

## 22. `activeVersion` 和 `latestVersion` 有什么区别？

**回答：** latestVersion 是最近受理的候选版本，activeVersion 是当前参与查询的 READY 版本。上传 v2 后，latestVersion 会立即指向 v2，但 activeVersion 仍是 v1；只有 v2 完成 canonical、retrieval 和双索引验收后，激活事务才会把 activeVersion 切到 v2。这个区分是实现无损版本更新的关键。

查询接口从不根据“版本号最大”自行推断可见版本，而是读取 Document 明确保存的 activeVersion；处理器激活时则要求候选仍是 latestVersion，防止较早的慢任务覆盖后来上传的版本。这样 v2 失败时 v1 继续服务，v2 尚未完成而 v3 已受理时，v2 即使晚到也没有资格抢占 activeVersion。

## 23. 一个文档版本满足哪些条件后才能进入 `READY`？

**回答：** 原文件必须通过大小和 SHA-256 复核，canonical 对象必须完成结构、位置和摘要校验，retrieval JSONL 的检索卡与 2560 维向量必须通过 Schema 和血缘校验，Evidence 与 Navigation 索引的实际数量必须等于预期数量，并且 Cluster UUID、Index UUID、Mapping 版本和投影指纹一致。最后激活事务还会校验它仍是 latestVersion、Job 仍由当前 owner 持有，全部满足才置为 READY。

校验不是在内存里算完就直接改状态：各阶段先形成持久 manifest 或 ProjectionReceipt，激活事务按固定锁顺序重新加载并核对这些事实。预期条数来自 canonical/retrieval，实际条数来自目标 ES 作用域，二者不相等就拒绝激活。READY 因此代表当前版本已满足服务条件，而不是“某个异步方法没有抛异常”。

## 24. 为什么新版本处理失败时旧版本还能继续服务？

**回答：** 新版本从受理到处理完成都只更新 latestVersion，不会提前修改 activeVersion。查询快照只读取 Document 当前 activeVersion 且 Version 状态为 READY 的记录。失败时 Consumer 只把候选 Version 和 Job 标为 FAILED，旧 activeVersion 没有变化，因此在线查询不受影响。

对象存储和 ES 中即使存在 v2 的部分产物，也因为查询过滤只包含 v1 的 versionId 而不可见；后续可重试任务可以复用或重建 v2 产物。只有显式删除会先清空 activeVersion 让文档立即停止检索。更新失败与删除语义分开，避免把一次解析或模型依赖故障扩大成已有知识不可用。

## 25. 如何保证查询不会同时读到新旧两个版本？

**回答：** `QueryAccessServiceImpl` 在鉴权完成后用一条 MySQL 查询读取知识库当前全部 activeVersion，并构造不可变快照和 SHA-256 指纹。BM25、KNN、canonical 读取以及 Agent 工具都复用这份 `QueryAccessContext`，Elasticsearch 两路查询也直接过滤这组 versionId。即使请求执行期间发生版本切换，本次请求仍然只使用开始时固定的版本集合。

Answer 可能执行多次 search/open，更需要固定快照，否则前半段在 v1 搜索、后半段在 v2 打开会产生无法解释的混合引用。Redis 的 snapshotFingerprint 也绑定这组版本，版本切换后相同 Idempotency-Key 不能重放旧结果。这里提供的是单请求内的一致数据视图，不是跨请求永远返回相同版本。

## 26. 文档删除为什么要分为立即停止检索和异步物理清理？

**回答：** 对象存储和 Elasticsearch 清理无法与 MySQL 组成一个本地事务，而且可能耗时或失败。因此删除受理事务先将 Document 从 ACTIVE 改为 DELETING，并立即把 activeVersionId 清空，同时创建 DeletionJob 和 Outbox。这样新查询马上看不到文档；后台再幂等删除各版本的 ES 投影、canonical、retrieval 和原文件，全部成功后才提交 DELETED 墓碑。

删除 Worker 会枚举该 Document 的所有版本，并对 bucket、前缀和作用域做校验后逐类清理；任一外部删除失败，Job 保持可诊断失败状态，不能提前删除 MySQL 清单。全部成功后才移除 artifact manifest、标记 content_deleted 并提交 DELETED，同时保留必要墓碑。这样既满足立即下线，也保留失败后继续清理的依据。

## 27. Transactional Outbox 解决了什么问题？

**回答：** 它解决的是数据库业务事实提交和 RabbitMQ 发送之间的可靠衔接。版本、Job 和 OutboxEvent 在同一个 MySQL 事务中提交，事务成功后 Publisher 再领取事件并发送。RabbitMQ 不可用时事件仍保持 PENDING，恢复后可以继续发布；消息已发送但状态没更新时允许再次发布，重复副作用由 Consumer 幂等处理。

Publisher 使用 `FOR UPDATE SKIP LOCKED` 分批领取事件并设置 owner/lease，事务外发送持久消息，只有 Confirm ACK 且没有 Return 才按 owner 标记 SENT。这个方案消除了“业务已提交但没有任何待发记录”的窗口，但无法消除“消息已发、SENT 未提交”的重复窗口，所以必须与 Consumer 幂等一起理解，不能单独宣传为 Exactly Once。

## 28. Consumer 如何保证重复消息不会产生重复业务数据？

**回答：** Consumer 先重新加载 Job、Version 和 Document，交叉验证消息里的稳定 ID，然后对 ProcessingJob 做条件租约竞争。已经 SUCCEEDED 的消息直接 ACK，未过期租约由其他实例持有时也 ACK 当前重复副本。canonical、retrieval 和 projection 都以 documentVersionId 为幂等身份，并通过数据库唯一约束、对象摘要和确定性 ES ID 复用或重建，因此至少一次投递不会产生第二个业务版本。

每个外部阶段都要验证“已有产物是否合法”，而不是只看存在性：canonical/retrieval 比较 size、SHA 和 lineage，ES 比较 mapping、UUID、指纹与条数。激活提交还必须持有当前 owner，并要求候选仍为 latestVersion。这样重复消息可能导致额外检查或安全重投，但无法绕过状态机产生第二次激活效果。

## 29. 简历中的“100份真实 PDF 全部进入 READY”具体代表什么？

**回答：** 它表示 100 份公开数据集真实 PDF 都经过正常上传、RabbitMQ、DeepDoc、检索卡生成、百炼 Embedding、Elasticsearch 双索引和激活链路，最终 MySQL 中 100/100 为 READY，且 Evidence 78,062/78,062、Navigation 2,422/2,422 与预期一致。它证明的是当前 300 页边界内这批文本型 PDF 的完整入库可行性，不代表 OCR、视觉理解或复杂表格问答也达到同样效果。

这个数字不是通过测试代码直接插库，也不是只验证上传接口返回 202，而是检查了最终版本状态、双索引预期/实际数量和真实依赖链路。它能支撑“全链路入库验收”这一工程亮点，但样本量仍只有 100 份，执行也不等于并发压力测试。简历中不能把它改写成“支持海量文档”或“高并发稳定入库”。

## 30. 这个项目最能体现你 Java 后端能力的是哪一部分？

**回答：** 我认为是跨 MySQL、对象存储、RabbitMQ、Elasticsearch 和 Redis 的状态边界设计。代码没有追求分布式事务或“恰好一次”口号，而是用 MySQL 事实、Outbox、租约、条件更新、稳定 ID、补偿清理和可重建投影形成可验证的一致性链路。AI 只是其中一个依赖，真正决定系统正确性的仍然是 Java 层的状态机、事务和失败处理。

如果让我在面试中展开，我会用三个崩溃窗口说明：对象写完但受理事务失败时精确补偿；消息发出但 Outbox 未标记时允许重复并由 Consumer 幂等；ES 半写成功时按版本清理重投且不激活。再加上凭据即时撤销、activeVersion 快照和 Agent Evidence 能力控制，这些都是传统 Java 后端在 AI 应用中仍然需要承担的核心职责。

## 31. 如果数据库提交后直接发送 RabbitMQ，会出现什么一致性问题？

**回答：** 主要有两个断点。先提交数据库再发消息，进程可能在两者之间崩溃，留下永远不处理的版本；先发消息再提交数据库，Consumer 又可能查不到业务事实，或者处理一笔最终回滚的数据。项目因此把 Job 和 OutboxEvent 与业务数据放进同一个 MySQL 事务，消息发布只消费已经提交的 Outbox 事实。

具体到上传场景，受理事务会同时写入候选 `DocumentVersion`、`ProcessingJob` 和 `OutboxEvent`，Controller 不直接把“发消息成功”当作受理条件。独立 Publisher 之后再领取 Outbox 并发布，所以 RabbitMQ 即使短暂不可用，也只是让版本停留在 PROCESSING，不会丢掉待处理事实。这里解决的是数据库与消息队列之间的可靠衔接，不是把两个系统变成一个分布式事务。

## 32. Outbox 事件为什么必须和业务数据在同一个事务里？

**回答：** 因为 Outbox 的价值就是让“需要发生的异步工作”本身成为业务事务的一部分。上传或删除受理只要提交成功，就一定同时存在对应事件；事务回滚时两者一起消失。这样 Publisher 可以单独失败和重试，但不会猜测某个版本到底需不需要处理。

如果 Outbox 在事务提交后再补写，仍然会有“业务成功但事件未落库”的崩溃窗口；如果提前单独写，又会出现业务回滚但事件仍被发布。项目还对同一 Job 的 Outbox 建立唯一约束，避免接口重试重复创建待发布事件。这样受理层只需要保证一个本地事务，跨系统的不确定性被推迟到可重试的发布阶段。

## 33. Outbox Publisher 如何领取和发布事件？

**回答：** Publisher 分批领取 PENDING 事件，SQL 使用 `FOR UPDATE SKIP LOCKED` 避免多个发布实例抢到同一批记录，并把事件改为带 owner 和 lease 的 PUBLISHING。随后在事务外发布到 RabbitMQ；只有收到 Broker Confirm 且消息没有被 Return，才按 owner 条件标记 SENT。发布失败或租约过期后，事件可以回到可领取状态继续处理。

当前配置每批最多领取 50 条，发布租约默认 30 秒，Confirm 等待 5 秒。网络调用不放在领取事务里，否则一个慢 Broker 会长期占用数据库锁；标记 SENT 时再次校验 owner，防止旧 Publisher 在租约已经被接管后覆盖新状态。这个流程允许重复发布，但不会因为发布实例宕机把事件永久锁死。

## 34. RabbitMQ Publisher Confirm 和 mandatory return 分别解决什么问题？

**回答：** Confirm 判断 Broker 是否接收了消息，但 ACK 不等于消息一定路由到了目标队列。因此代码同时开启 mandatory 和 returns：只有 Broker ACK 且没有 Return 才认定发布成功。前者覆盖 Broker 接收失败，后者覆盖 exchange 存在但 routing key 无法路由的情况。

消息本身以 persistent JSON 发送，并携带 correlation 信息关联发布结果。代码不会在 `convertAndSend` 返回时立即标记 Outbox 成功，而是等待相关 Confirm，并检查 Return 回调。如果 Confirm 超时、NACK 或发生不可路由，Outbox 都保留为可恢复状态；所以“调用 RabbitTemplate 没抛异常”不等于可靠发布成功。

## 35. 消息已经发出，但 Outbox 还没标记 SENT 就宕机会怎样？

**回答：** 恢复后租约会过期，Publisher 可能再次发布同一事件，所以这里不能承诺消息只投递一次。项目接受重复投递，让 Consumer 根据 Job 状态、租约、稳定 ID 和各阶段幂等约束消除重复副作用。这是典型的至少一次投递设计。

例如第一次消息已经把版本处理为 READY，但 Publisher 在更新 Outbox 前宕机，第二次消息到达时 Listener 会重新读取 `ProcessingJob`，发现状态已经是 SUCCEEDED，直接 ACK。若第一次只完成了 canonical，后一次则通过 manifest、摘要和 versionId 识别已有合法产物，从后续阶段继续。重复的是执行机会，不是再创建一个文档版本。

## 36. 这个系统能否宣称 Exactly Once？

**回答：** 不能。MySQL、RabbitMQ、对象存储和 Elasticsearch 之间没有统一事务，发布确认与数据库状态更新之间始终存在崩溃窗口。项目做到的是至少一次投递加业务幂等，并尽量保证处理结果只生效一次；我会明确区分“效果上的幂等”与协议层的 Exactly Once。

我在面试中不会用“最终一致性”四个字掩盖具体机制：消息可能重复，外部写入可能执行多次，但每个阶段都有稳定身份和验收条件，真正的可见性只在 MySQL 激活事务中改变。因此更准确的说法是“at-least-once delivery，idempotent processing，single activation effect”，而不是没有证据的 Exactly Once。

## 37. 为什么选择至少一次投递，而不是追求严格恰好一次？

**回答：** 严格恰好一次需要所有参与者共享事务语义，在当前组件组合里成本很高且仍容易形成错误安全感。至少一次配合可验证的幂等身份、状态机和唯一约束更符合工程现实：消息可以重复，但不会重复创建版本、重复激活或制造不可解释的数据。

这个选择也让恢复路径更简单：Consumer 不依赖某个不可重放的内存步骤，而是随时从 MySQL 和 artifact manifest 重新判断已经完成到哪里。对象存储依靠随机键、摘要和清单，ES 依靠确定性文档 ID 与作用域重建，激活依靠行锁和条件更新。相比维护跨资源协调器，这种方案更容易测试每个崩溃窗口。

## 38. RabbitMQ 暂时不可用时，上传请求会怎样？

**回答：** 上传受理事务仍可提交，DocumentVersion、ProcessingJob 和 OutboxEvent 都保存在 MySQL 中，接口返回的是已受理而不是已完成。Outbox 保持 PENDING，Publisher 在 RabbitMQ 恢复后继续发送；期间候选版本维持 PROCESSING，旧 activeVersion 继续服务。这里不把消息系统短暂故障扩散成已激活版本不可查。

但这不代表系统会假装处理已经完成。客户端查询版本状态时仍会看到 PROCESSING，运维也能通过 Outbox 的 PENDING/PUBLISHING 状态定位积压。如果这是某份文档的首次上传，那么在 READY 前它不会进入检索；如果是替换版本，则旧 activeVersion 持续可用。恢复后的目标是继续投递，而不是让上传请求同步等待 Broker。

## 39. 为什么消息体只携带稳定 ID，不直接携带对象地址和处理参数？

**回答：** 消息中的 tenant、knowledgeBase、document、version、job、event 等 ID 用来定位任务，但对象地址、版本状态和配置都必须从 MySQL 重新读取。否则旧消息或伪造消息可能携带过期路径和越权上下文。稳定 ID 加数据库事实校验既减小消息体，也让重试统一服从当前业务状态。

例如对象键、版本号、当前状态和所属客户都由 Consumer 根据 ID 查询，并交叉验证 Job、Version、Document、KnowledgeBase 的关系。即使一条历史消息晚到，数据库中的 SUCCEEDED、FAILED、DELETING 等状态仍决定是否允许执行。消息只表达“请尝试处理这个持久任务”，不携带足以绕过领域校验的执行指令。

## 40. Consumer 为什么还要重新加载并交叉验证数据库事实？

**回答：** RabbitMQ 只是传输层，不是可信业务事实源。Listener 会校验消息各 ID 的归属关系，再加载 Document、Version 和 Job，确认它们属于同一作用域且状态允许处理。这样即使收到陈旧、重复或字段被污染的消息，也不会仅凭消息内容访问对象存储或修改别的文档。

校验之后还要竞争 Job lease，只有拿到 ownerToken 的 Worker 才进入处理器。后续激活事务会再次锁定并验证 Document、Version、Job 和 Projection，而不是沿用消费开始时的旧判断。这种“入口校验 + 提交前再校验”是为了覆盖长任务执行期间状态发生变化的窗口。

## 41. ProcessingJob 的 lease 和 ownerToken 有什么作用？

**回答：** lease 表示某个 Worker 在一段时间内拥有处理权，ownerToken 则用于所有续租和状态提交的条件更新。只有持有当前 owner 的实例才能续租、成功或失败结算，旧 Worker 即使在超时后恢复，也不能覆盖已经被新 Worker 接管的结果。这解决的是长任务中的并发执行和僵尸实例问题。

它和普通的 `status=RUNNING` 不同：仅有状态无法判断 Worker 是正在工作还是已经宕机，也无法阻止超时旧实例回来提交。代码把 `leaseUntil` 和 `ownerToken` 一起写入 Job，heartbeat、失败结算和激活都带 owner 条件。条件更新影响行数不符合预期时，当前 Worker 必须停止把自己当作任务所有者。

## 42. 为什么处理任务还需要心跳？

**回答：** 文档解析、Embedding 和索引可能持续较久，固定短租约会在正常执行期间过期。当前默认 Job lease 为 15 分钟，心跳每分钟按 owner 续租，让健康 Worker 保持所有权；如果进程真的退出，心跳停止，过期租约又允许后续恢复，而不是永久卡死。

心跳独立于某一步外部调用的成功与否，只更新很小的数据库状态，不会把整个处理过程包进长事务。续租失败意味着 owner 已变化、任务被取消或数据库不可用，处理器不能继续无条件激活。租约时间、心跳间隔都是配置项，生产上应结合最大单步耗时和故障检测速度调整，而不是把 15 分钟当作性能指标。

## 43. Worker 崩溃后任务如何被其他实例接管？

**回答：** Worker 崩溃后不会再续租，恢复逻辑会把符合条件的过期任务重新置于可处理状态，后续投递再竞争新的 owner。各阶段都围绕 versionId 做幂等检查：已有且摘要匹配的产物可复用，不完整投影会按作用域重建，最后激活还要重新校验完整性。因此接管不是从内存断点继续，而是从持久化事实安全重放。

恢复时最重要的是不相信旧 Worker 的局部进度。例如 manifest 存在但对象摘要不符，不能因为“看起来做过”就跳过；ES receipt 与当前 cluster/index UUID 或计数不一致，也必须重建。新 owner 最终只有在 Document、Version、Job 和投影全部满足条件时才能激活，所以旧 Worker 与新 Worker 短暂重叠也不会同时提交成功。

## 44. 为什么重试延迟设计为 5、30、300 秒？

**回答：** 这是分级退避：5 秒吸收短暂抖动，30 秒覆盖稍长的依赖恢复，300 秒避免持续故障时快速打满下游。它不是算法上的最优常数，而是当前实现的运维参数；达到上限后进入失败和 DLQ 路径，避免无限重试掩盖永久错误。

每次失败都会记录稳定错误码、是否可重试和 attempt，Listener 根据 attempt 选择对应重试路由。这样 DeepDoc、Embedding 或 ES 故障时不会在主队列里热循环，也不会用一个超长延迟拖慢所有瞬时错误。后续如果有生产监控数据，我会根据依赖恢复时间分布调整退避并加入抖动；当前不能把这组三段参数说成经过大规模压测验证。

## 45. RabbitMQ 延迟重试为什么不用 Consumer 线程 sleep？

**回答：** sleep 会长期占用 Consumer 线程和未确认消息，降低吞吐，也让进程重启时丢失等待状态。项目把失败消息确认发布到不同 TTL 的重试路由，再 ACK 原消息；TTL 到期后重新路由回工作队列。等待由 Broker 持久化管理，Consumer 可以立即处理别的任务。

顺序也很关键：代码先 Confirm 新的重试消息，确认成功后才 ACK 当前消息；如果重试发布失败，就 NACK 并 requeue 原消息。否则会出现“原消息已确认，但重试消息并未真正进入 Broker”的丢任务窗口。DLQ 转发同样遵循先可靠发布、再确认原消息的原则。

## 46. 如何区分可重试错误和永久错误？

**回答：** 处理链把异常归一为稳定错误分类，并携带 retryable 语义。网络超时、服务暂不可用这类依赖错误进入分级重试；格式不支持、数据校验失败、摘要不一致或业务状态非法通常直接判为永久失败。对外和入库只保存安全、稳定的错误信息，不直接暴露第三方响应或堆栈。

判断依据不是简单看异常类名，而是看重试是否可能改变结果。例如 HTTP 连接超时可能恢复，解析返回结构违反约定则重复调用通常仍会失败；ES 临时不可达可重试，artifact SHA 不一致则说明数据完整性已经破坏。永久失败会把 Job/Version 落成可诊断状态并进入 DLQ，不会无限消耗外部模型和队列资源。

## 47. DLQ 在项目中承担什么职责？

**回答：** DLQ 承接已经超过自动重试预算或确定不可自动恢复的消息，避免坏任务无限循环占用主队列。Listener 只有在确认把消息发布到 DLQ 后才 ACK 原消息；若 DLQ 发布本身失败，则 NACK requeue，防止任务静默丢失。业务 Job 同时记录 FAILED，便于定位和受控重试。

DLQ 不是业务事实的唯一记录，真正的失败原因、attempt 和 retryable 标志仍在 MySQL Job 中；队列中的消息主要用于运维隔离和排查。人工重试也不是简单把旧消息原样塞回主队列，而是根据当前状态创建受控的新 attempt，继续使用数据库事实和幂等阶段，避免绕过状态机。

## 48. 写 Elasticsearch 一半时 Worker 崩溃，重试会不会产生脏数据？

**回答：** Projection 以 tenant、knowledgeBase、documentVersion 为作用域，文档 ID 是确定性的。重试时先清理该版本作用域，再根据已经校验过的 canonical 和 retrieval 产物重新批量写入，最后检查索引映射、集群和索引 UUID、摘要及实际条数，验收通过才写 ProjectionReceipt。半成品没有资格进入激活事务。

这里没有采用“记录最后写到第几条再续写”，因为批量写入成功边界和 ES refresh 可见性会让断点很难证明。按版本作用域删除后全量重投更简单，也能利用确定性 `_id` 保证重放结果一致。代价是大文档失败时会增加重建成本，但当前更重视正确性，且还没有并发压测数据证明需要更复杂的增量恢复。

## 49. 激活成功后、消息 ACK 前宕机会怎样？

**回答：** RabbitMQ 会再次投递，但数据库里的 Job 已经是 SUCCEEDED，Listener 重新加载后会直接 ACK，不再执行完整链路。激活事务本身也使用锁、状态和 owner 条件，不会把同一版本重复切换。因此这个崩溃窗口最多带来一次重复消息，不会生成第二次业务效果。

激活是一个短 MySQL 事务，锁顺序固定为 Document、Version、Job、Projection，并同时把 Version 改为 READY、Document 的 activeVersion 指向候选版本、Job 改为 SUCCEEDED。三个事实要么一起提交，要么一起回滚。即使 ACK 丢失，再消费时判断的也是已提交结果，而不是依赖本地变量记住“刚才成功过”。

## 50. 你如何验证重复投递、崩溃恢复和租约竞争？

**回答：** 我会分层验证：Mapper 和 Service 测条件更新、owner 不匹配、租约过期和唯一约束；消息集成测试覆盖重复消息、Confirm/Return、重试发布失败时 NACK；链路测试在 canonical、retrieval、projection、activation 前后注入失败，再重放同一 jobId，检查只有一个版本被激活、条数和摘要一致。项目现有测试也围绕这些状态边界，而不是只测一条成功路径。

验收时我不会只看接口最终返回成功，还会核对数据库状态组合是否可能出现非法中间态，例如 READY 但 ProjectionReceipt 不完整、Job SUCCEEDED 但 activeVersion 未切换，以及同一版本出现两份 manifest。对于外部组件则检查对象 SHA、ES 实际计数和消息 ACK 行为。需要强调的是，这些属于功能与故障注入验证，项目尚未完成正式并发压测。

## 51. 为什么原文件放对象存储，而不是放 MySQL BLOB？

**回答：** 原文件最大可到 50 MiB，放 BLOB 会放大数据库备份、复制、连接占用和事务压力，也不利于流式读取。MySQL 更适合保存对象键、大小、SHA-256 和生命周期状态，对象字节交给 SeaweedFS 的 S3 接口保存。这样数据库仍掌握事实和完整性，文件存储可以独立扩容。

`S3SourceObjectStore` 使用 AWS SDK v2 流式上传和读取，写入过程中计算实际字节数与 SHA-256，不需要把整个 50 MiB 文件放入 JVM 堆。数据库记录的是可验证引用，而不是只保存一个无法校验的 URL；后续解析前还会重新核对 size 和 digest。这样把大对象吞吐交给对象存储，同时保留 MySQL 对业务可见性和完整性的控制。

## 52. 代码如何隔离 SeaweedFS，避免业务层绑定具体厂商？

**回答：** 业务层依赖 `SourceObjectStore` 一类端口，基础设施实现用 AWS SDK v2 对接 S3 兼容协议。上传、打开、存在性检查、删除和前缀列举都通过抽象完成，业务只认识 bucket、objectKey、size 和 sha256。以后替换成其他 S3 兼容服务时，状态机和领域服务不需要重写。

项目还把源文件、canonical 和 retrieval artifact 的职责分开，即使底层共用 S3 兼容服务，Service 也不会随意跨前缀操作。Adapter 启动时通过 head bucket 验证连接，删除和清理前再次校验 bucket、前缀与作用域。这个抽象的重点不是为了展示接口数量，而是防止具体存储 SDK 和危险的宽范围删除泄漏进业务代码。

## 53. 为什么上传是先写对象存储，再提交数据库？

**回答：** 数据库受理记录需要引用一个已经完整写入并可校验的源对象；如果先提交数据库再上传，消费者可能立刻拿到一个不存在或未写完的对象。当前实现先流式写对象并计算大小和 SHA-256，再开启短事务创建版本、Job 和 Outbox。对象成功但数据库失败的窗口则通过精确补偿删除处理。

对象键使用 `source/{tenant}/{yyyy}/{MM}/{uuid}` 形式的随机键，客户端文件名不会直接变成存储路径，因此接口重试不会覆盖既有对象。进入受理事务后，代码把本次写入结果连同内容摘要保存到版本记录；Consumer 后续只接受数据库登记且复核一致的对象。这个顺序是在“数据库引用悬空”和“少量可补偿孤儿”之间选择后者。

## 54. 上传数据库事务失败时如何补偿对象存储？

**回答：** Coordinator 只删除本次请求刚生成的随机 objectKey，并且保留实际键和写入结果用于精确补偿，不会按宽泛前缀清空目录。删除失败会留下可识别的孤儿候选，由后续清理任务处理；不会为了补偿失败去伪造数据库成功状态。

补偿前目标键已经由本次调用返回，不依赖用户输入、通配符或重新计算路径，所以删除范围可证明。若数据库事务实际成功但客户端连接中断，上传幂等记录会让重试返回原受理结果，而不是先删除已经被版本引用的对象。补偿逻辑与孤儿清理共同覆盖跨系统没有原子事务这一事实，但都不会承诺绝对没有短暂孤儿。

## 55. 如何安全清理对象存储中的孤儿文件？

**回答：** 清理器只扫描规定 bucket 和受控前缀，对对象键做作用域校验，并设置宽限期，避免碰到仍在提交窗口内的新对象。随后用 MySQL 的 source、canonical、retrieval 等清单反查引用，只有确认没有任何持久化引用的对象才可删除；数据库不可用时应 fail-closed，而不是冒险删文件。

我会把“候选发现”和“执行删除”分开记录，先输出对象键、最后修改时间和引用检查结果，便于审计。对于 canonical 的并发唯一键竞争，失败的一方只清理自己刚写的随机对象，不能删除获胜方 manifest 指向的对象。安全原则是宁可暂时保留可回收孤儿，也不能误删一个 READY 版本依赖的 artifact。

## 56. canonical artifact 是什么，为什么要单独保存？

**回答：** canonical 是把不同格式解析结果归一后的、带来源坐标的不可变 JSONL 证据层。它保留文本、标题层级、页码或顺序位置、表格等结构元数据，并记录对象大小、SHA-256 和块数。检索、引用和重建 ES 都基于它，而不是重新解析原文件，因此结果可复现，引用也能回到确定的原文位置。

它还隔离了解析器输出协议与后续检索模型：DeepDoc 响应可以较复杂，但进入 canonical 后，Java 有统一的 EvidenceBlock、标题树和 SourcePosition 语义。`DocumentCanonicalServiceImpl` 写入临时 JSONL、上传随机对象、读回验 SHA 后才插入 manifest；如果相同 version 已有合法 manifest，就复用而不是生成第二份业务产物。

## 57. canonical JSONL 中保存哪些关键信息？

**回答：** 文件包含文档级头信息和按顺序排列的 EvidenceBlock，每个块有稳定 blockId、类型、文本、heading path、页码或格式对应的位置，以及必要的结构元数据；尾部还有计数和摘要用于完整性检查。具体字段按文档类型有所差异，但核心原则是正文与来源坐标绑定，不能只有一段脱离位置的纯文本。

读取时不会只信数据库中的“已生成”标志，Store 会校验对象字节数、SHA、记录顺序和尾部统计，再按 canonical address 精确取回块。PDF 主要使用页码和页内结构位置，DOCX、TXT、Markdown 则保留它们能够提供的顺序坐标。不同格式不伪造不存在的页码，但都能稳定定位到相同版本的原始内容单元。

## 58. EvidenceBlock 和传统固定长度 Chunk 有什么区别？

**回答：** EvidenceBlock 优先服从解析器识别出的自然结构，例如段落、标题、表格或代码块，而不是先把全文按固定 token 数硬切。这样引用可以落到有语义和来源边界的单元，避免固定窗口把表格、标题和正文切断。检索阶段仍可围绕这些块组织窗口，但 canonical 身份保持稳定。

固定 Chunk 的优势是实现简单、长度均匀，但更新切分参数后 ID 和引用位置容易整体漂移。DocQuery 把 blockId、ordinal、heading 和 source position 固化在 canonical 中，后续 Navigation 卡和 Evidence 投影都引用这些身份。必要时可以在 Answer 侧组合相邻块形成上下文包，但外部 Citation 仍回落到原始 EvidenceBlock，而不是引用临时拼接文本。

## 59. 为什么不对所有 EvidenceBlock 都生成向量？

**回答：** 当前设计把全文精确召回交给 Evidence 索引的 BM25，把向量预算集中在标题树和文档画像形成的 Navigation 卡片上。这样向量数量从正文块规模降到结构节点规模，减少 Embedding 和 KNN 存储压力；召回到导航节点后再展开对应章节证据。代价是语义召回更依赖文档结构，因此不能宣传为适合所有视觉或弱结构文档。

100 份 PDF 的最终入库中，Evidence 为 78,062 条，而 Navigation 为 2,422 条，这能直观看出两类规模差异。但项目没有做“全量块向量化”对照实验，也没有可靠的 Token 或成本下降比例，所以我只陈述架构上的调用数量差异和实际条数，不声称节省了某个百分比。

## 60. 标题层级、页码和块顺序是如何保留下来的？

**回答：** DeepDoc 返回格式化解析结果后，Assembler 会把 heading path、页码、块序号和格式特有坐标写进 canonical；Validator 再检查顺序、范围和必要字段。后续检索返回的不是重新拼出来的文本位置，而是 canonical 中已注册的 source position，因此版本、标题路径和页码可以稳定追溯。

标题节点与其覆盖的 block range 还会用于构建 RetrievalNode，使语义导航结果能够展开到对应章节。页码只在解析结果确实提供时返回，Java 会拒绝越界、逆序或缺少必填定位信息的响应。这样“第几页”不是大模型根据文字猜测出来的，而是解析、canonical、检索和 Citation 全链路传递的字段。

## 61. DeepDoc 在系统中承担什么职责？

**回答：** DeepDoc 是独立解析服务，负责把 PDF、DOCX、TXT、Markdown 转成受约束的结构化块；Java 侧负责上传、HTTP 调用、响应 schema 校验、canonical 组装和后续状态机。当前配置四种格式都默认走 DeepDoc。它是解析依赖，不承担租户鉴权、版本管理、检索或最终回答。

`DeepDocDocumentParser` 会校验 HTTP 状态、响应结构、块数量、文本长度以及不同格式的 source position，PDF 还检查页码和表格相关元数据。连接超时默认 5 秒，请求超时可到 20 分钟，因为大文件解析属于长任务；超时后由 Job 的可重试分类和消息退避处理，而不是让上传 HTTP 请求一直阻塞。

## 62. 为什么解析服务通过 HTTP 独立部署，而不是直接嵌进 Java？

**回答：** 文档解析生态和潜在的模型依赖更适合独立进程，HTTP 边界可以隔离 Python/原生库、资源占用和故障，并让 Java 服务保持清晰的事务与业务职责。代价是增加网络超时和 schema 演进问题，所以 Java 侧设置连接与请求超时，并严格校验响应，而不是盲信解析结果。

部署上，解析服务可以按 CPU、内存或 GPU 特征独立扩容，Java Worker 只持有任务 lease 并通过稳定协议调用。协议变化需要版本化和兼容测试，否则解析字段缺失会直接影响 Citation；因此 Java 会在 canonical 落盘前做一次强校验，把不合法输出挡在业务事实之外。当前并没有压测证明应该拆成更多服务，这个边界主要来自依赖与资源隔离。

## 63. 当前解析与问答对 OCR、复杂表格和跨页表格支持到什么程度？

**回答：** 代码能保留解析服务返回的表格与页码等结构信息，但最终 96.25% Answer 评测明确筛除了视觉/OCR、复杂表格、跨页表格和多步推理问题。因此我只能说系统为结构化来源信息预留了链路，不能声称这些场景已经被效果评测证明。

如果面试官追问，我会区分“能接收某类解析结果”和“问答效果已经验证”两个层次：前者是 schema 与工程能力，后者需要专门数据集、标注和人工复核。目前报告只支持常规单页文本问题；扫描 PDF 的 OCR 准确率、跨页单元格恢复和视觉图表理解都没有可引用指标，应列为后续工作而不是简历成果。

## 64. Evidence 索引和 Navigation 索引分别存什么？

**回答：** Evidence 索引保存可直接引用的正文证据，使用中英文兼容的文本字段做 BM25；Navigation 索引保存 DocumentProfile 和 RetrievalNode 等结构卡片，并带 2560 维向量用于 KNN。两者都写入 tenant、knowledgeBase、documentVersion 等过滤字段，但一个面向最终证据，一个面向语义导航。

`SearchIndexMappings` 对两个索引都使用 `dynamic: strict`，防止字段拼错被 ES 静默接收；Evidence 文本同时配置 CJK 与 standard 分析字段，Navigation 的 dense_vector 使用 cosine。投影收据分别记录两个索引的预期和实际条数，激活前必须共同通过，所以双索引是职责拆分，不是两个可以独立生效的数据版本。

## 65. 为什么要做 Elasticsearch 双索引，而不是所有字段放进一个索引？

**回答：** 两类文档的查询方式、映射和生命周期验收不同：Evidence 关注全文文本、来源坐标和 BM25，Navigation 关注结构摘要和 dense_vector。拆分后可以分别设置严格 mapping、统计预期条数和诊断投影问题，也避免大量正文记录都携带向量字段。逻辑版本仍通过同一个 ProjectionReceipt 和激活事务保持一致。

如果放在一个索引里，就需要用类型字段区分完全不同的查询与稀疏字段，还容易让“导航命中”和“可引用证据”混为一谈。当前 Gateway 明确从 Evidence 做关键词召回、从 Navigation 做 KNN，再在 Java 中融合并回到 canonical。代价是投影和运维需要维护两个 alias/index，但换来了更清晰的职责、映射和验收计数。

## 66. DocumentProfile 和 RetrievalNode 是如何生成的？

**回答：** canonical 完成后，检索卡构建器先依据文档标题、结构和内容生成一个文档级 DocumentProfile，再为非根标题节点生成 RetrievalNode，记录标题路径、覆盖的 EvidenceBlock 范围和用于导航的文本。随后按批次调用 Embedding，产物和 lineage 一起写入 retrieval artifact，供投影阶段校验和写入 Navigation 索引。

Embedding 当前维度为 2560、默认批次为 20，Service 会检查每条向量维度、节点身份和 canonical lineage，不能把缺向量或属于旧 canonical 的卡片投影进去。DocumentProfile 负责文档级入口，RetrievalNode 负责章节级入口；它们都保存要展开的 block 范围，而不是复制后就脱离原文。这样重建 Navigation 时仍能证明每张卡来自哪个版本和哪些 Evidence。

## 67. 为什么检索卡只用于导航，不能直接作为最终引用？

**回答：** 检索卡包含摘要和结构化压缩文本，可能不是原文逐字内容，也不一定对应单一页码，所以不能作为事实证据。它只负责帮助定位文档或章节；真正提交给 Answer 的引用必须展开到 canonical 中已注册的 EvidenceBlock。这样把“找方向”和“证明结论”分开，避免引用模型生成的摘要。

代码层面，Navigation 命中返回的是文档或标题节点地址，`open` 再根据该地址读取覆盖范围内的 canonical 块并注册 E#。Finalizer 的允许集合只包含这些原文 Evidence，不包含检索卡文本。即使卡片摘要写得很像答案，Java 也不会把它直接转换成 Citation，这是可追溯边界中很重要的一条。

## 68. 只给导航卡做向量会不会损失正文语义召回？

**回答：** 会存在这个权衡。项目用标题树和文档画像覆盖章节语义，同时由 BM25 对正文做精确召回，再通过 RRF 合并两路结果，减少全量正文向量的成本和复杂度。但弱标题、纯扫描件或语义与标题偏离的内容可能受影响，所以最终以真实评测衡量，不能把这种设计描述成对所有文档都更优。

最终 Retrieve 评测给出的事实是 80 个顺序请求全部成功，DocHit@5 为 92.5%、DocHit@10 为 96.25%，说明当前 43 份文档对应的问题集上整体可用，但仍有未命中的样本。要判断损失究竟来自导航向量策略、查询表达还是原始解析，需要做全块向量化、不同卡片策略的对照实验；目前没有这组实验，所以我不会给出超出报告的因果结论。

## 69. BM25 和 KNN 两路查询具体如何执行？

**回答：** BM25 查询 Evidence 索引，对正文、standard 子字段、标题路径和文档标题设置不同权重；KNN 查询 Navigation 索引，用请求向量匹配 2560 维 cosine 向量。两路都在查询阶段过滤 tenantId、knowledgeBaseId 和本次快照中的 active documentVersionId，HYBRID 模式下并行执行后再融合。

`ElasticsearchSearchRetrievalGateway` 将关键词支路和语义支路封装成独立查询，HYBRID 由 `RetrieveServiceImpl` 使用受控 executor 并行等待。ES 返回 partial search results 时按失败处理，不能把分片不完整的结果当成正常召回。命中后 Java 还会依据 canonical 地址读回原文并校验作用域，ES `_source` 不是最终引用事实。

## 70. 为什么不能直接把 BM25 分数和向量相似度相加？

**回答：** 两种分数的量纲和分布不同，而且会随查询、索引和模型变化，直接相加需要脆弱的归一化和权重标定。项目使用基于名次的融合，只关心每路排名而不是原始分数，因此对分数量纲更稳健，也更容易解释和复现。

例如 BM25 分数受词频、字段长度和权重影响，cosine 通常落在另一个范围，简单设置 `0.5 + 0.5` 并没有稳定含义。RRF 的代价是丢失一部分分数差距信息，但当前没有足够标注数据去可靠训练或调优跨路校准器，所以选择更保守的 rank-based 融合；之后的 qwen3-rerank 只在 Answer 小候选集上补充细粒度判断。

## 71. RRF 的计算方式是什么？

**回答：** 对每一路结果按 rank 计算 `1 / (rrfK + rank)`，同一候选在多路出现时把贡献相加，再按总分排序。当前默认 `rrfK=60`。它让同时被关键词和语义检索命中的候选获得更高优先级，同时避免某一路异常大的原始分数支配结果。

融合前会把两路命中归一到可以合并的文档/证据身份，并保留各支路排名用于解释；如果某候选只在一路出现，也会获得该路贡献，不会被直接丢弃。得分相同时还需要稳定的次级排序，确保相同请求在索引不变时结果可复现。`rrfK=60` 是当前配置值，不是通过公开 benchmark 证明的全局最优参数。

## 72. 当前混合检索的关键参数有哪些？

**回答：** 配置中公开检索默认 `topK=5`、最大 20；BM25 候选 50；KNN 默认 `k=20`、`numCandidates=100`；RRF 的 `k=60`。Evidence BM25 主要字段权重是正文 4、正文 standard 3、heading 2 和 1.5、title 1.2 和 1.0。这些是当前工程参数，不应包装成普适最优值。

参数选择体现的是先扩大两路候选、再融合裁剪：BM25 50 条有利于长尾词命中，KNN 的 `numCandidates=100` 先提高近邻搜索候选，再取 20 条参与融合；接口层再限制最终 topK，防止调用方无界放大响应。调参应同时观察 DocHit、MRR、页码覆盖和延迟，不能只追一个命中率数字，也不能用顺序评测推导并发容量。

## 73. Rerank 用在哪里，失败时如何处理？

**回答：** 当前 Rerank 用在 Answer 内部的候选选择，不改变独立 Retrieve 接口的最终评测口径。系统把一定规模的候选交给 `qwen3-rerank`，当前候选池上限为 20；调用超时或失败时记录降级并保留原候选顺序，不让辅助排序依赖直接导致整次问答失败。

Rerank 的输入由 Java 从已授权、已固定版本的候选构造，输出只能调整现有候选顺序，不能凭空创建证据或扩大访问范围。默认超时 15 秒、重试 1 次；即使模型返回非法索引，也要回退而不是采用未知结果。最终证据仍需经过 open、注册和 submit 校验，所以精排模型不在安全边界上。

## 74. RRF 和 Rerank 的职责有什么不同？

**回答：** RRF 是无模型的多路召回融合，用于把 BM25 和 KNN 的名次合并成统一候选集；Rerank 是后置相关性判断，对较小候选池做更细排序。前者强调稳定、低耦合和可解释，后者用额外延迟换取精排能力，所以失败时可以退回 RRF 结果。

执行顺序上先保证召回面，再对 Answer 需要处理的有限候选做精排，避免把全库内容交给 Rerank。独立 Retrieve 报告中的 P95 和命中率来自 HYBRID 检索本身，不能把 Answer 内 Rerank 的效果混入其中。若要量化 Rerank 增益，需要同一题集上的开关对照，目前文档没有提供这一独立提升比例。

## 75. 权限过滤为什么必须下推到 Elasticsearch 查询阶段？

**回答：** 如果先全局召回再在 Java 里过滤，越权文档可能已经参与 TopK 竞争、日志或后续模型输入，而且合法结果会被它挤掉。项目在 BM25 和 KNN 两路都同时过滤 tenant、knowledgeBase 和 active version 集合，从候选生成阶段就排除无权数据；Java 层再做来源注册和快照校验，形成纵深防御。

active version 集合来自鉴权完成后的单次 MySQL 快照，不接受客户端直接提交 versionId。ES 的每条投影也冗余 tenantId、knowledgeBaseId、documentVersionId 供前置过滤；canonical 回读时再核对该地址是否属于同一 `QueryAccessContext`。这样即使索引中残留旧版本或别的客户数据，它们也没有机会进入当前请求的候选集合。

## 76. 查询向量生成失败时，混合检索如何降级？

**回答：** 在 HYBRID 模式下，BM25 是基础路径；Embedding 或语义检索失败时，服务显式标记降级并返回 KEYWORD 结果，而不是伪装成完整混合检索。纯 SEMANTIC 模式则直接返回依赖错误。反过来，关键词检索失败不能用 KNN 静默掩盖，因为最终证据展开依赖正文索引。

响应和审计会记录实际执行模式，让调用方能够区分“配置请求 HYBRID”和“本次实际只完成 KEYWORD”。最终 Retrieve 评测的 80 个请求中降级数为 0，所以报告里的命中率确实对应完整混合路径；这并不意味着生产中永远不会降级，而是说明该批评测没有触发依赖故障。

## 77. 为什么 Elasticsearch 只写入一部分数据时版本不能 READY？

**回答：** 激活服务要求 canonical、retrieval 和 projection 的 lineage、摘要、索引身份及预期/实际条数全部一致。ProjectionService 还会检查 Evidence 与 Navigation 的实际计数，只有验收完成才生成收据。缺任一部分时 Version 保持 PROCESSING 或转 FAILED，Document 的 activeVersion 不变，因此在线查询不会看到半成品。

不能只看 Bulk API 返回“部分成功”后就继续，因为缺少的恰好可能是答案所需证据。`DocumentVersionActivationServiceImpl` 是唯一允许修改 activeVersion 的入口，事务中还会验证候选仍是 latestVersion、Job 仍由当前 owner 持有。即使旧 Worker 延迟完成了 ES 写入，只要它失去租约或版本已不是最新，也不能把结果激活。

## 78. Elasticsearch 数据丢失后如何重建？

**回答：** ES 被定义为可重建投影，事实仍在 MySQL、源文件和对象存储中的 canonical/retrieval artifact。重建时校验 artifact 大小、SHA-256 和 lineage，再按 version 作用域删除旧投影并使用确定性 ID 写回双索引，最后重新验收集群、索引 UUID、mapping fingerprint 和条数。不会反向把 ES 当成原始事实恢复 MySQL。

ProjectionReceipt 记录当时投影所对应的 cluster/index 身份，因此重建集群或索引后，即使 alias 名相同，UUID 不匹配也会暴露旧收据已经失效。恢复流程应遍历 MySQL 中仍需服务的 READY 版本重新投影并验收，再恢复查询流量。当前项目验证了可重建机制和 100 份 PDF 的投影一致性，但没有做大规模灾备恢复时长压测。

## 79. Retrieve 和 Answer 都是读请求，为什么还需要 Redis 幂等？

**回答：** 它们虽然不修改文档，但可能触发 Embedding、Rerank 和大模型等昂贵依赖。客户端超时重试若重复执行，会造成重复调用和不一致结果。Redis 幂等让同一业务请求在执行中返回 IN_PROGRESS，完成后短期重放相同结果，同时仍把鉴权和 activeVersion 快照放在缓存判断之前。

这里解决的不是传统写接口的“防止重复落库”，而是“防止同一逻辑查询重复占用外部资源”。当前 RUNNING TTL 默认 10 分钟，成功结果 TTL 默认 5 分钟，序列化结果上限 1 MiB；超过限制或状态异常时不会强行缓存。审计与授权仍按当前请求执行，不能因为有历史结果就跳过凭据撤销检查。

## 80. Redis 幂等记录有哪些状态？

**回答：** 核心状态是 RUNNING 和 SUCCEEDED。RUNNING 保存请求指纹、快照指纹、ownerToken 和租约，用于阻止并发重复执行；SUCCEEDED 保存同一请求的序列化结果并设置较短结果 TTL，用于安全重放。失败不会缓存成成功结果，持有者只能用匹配 owner 释放自己的 RUNNING。

claim 可能返回 OWNER、IN_PROGRESS、REPLAY 或不同类型的 CONFLICT：OWNER 才能真正调用下游，IN_PROGRESS 告诉相同请求已有执行者，REPLAY 返回已完成结果，指纹冲突则拒绝幂等键误用。这个状态机都存放在一个 Redis Hash 中，状态迁移由 Lua 原子完成，不依赖单实例 JVM 内存。

## 81. 为什么 claim、renew、complete 要用 Lua 脚本？

**回答：** 这些操作都需要“读取状态、比较指纹或 owner、再修改 TTL/内容”作为一个原子动作。如果拆成多条 Redis 命令，两个请求可能同时认为自己获得所有权，或旧 owner 覆盖新结果。Lua 把状态机判断放在 Redis 单线程执行边界内，避免进程内锁无法覆盖多实例的问题。

例如 `complete` 必须同时验证状态仍为 RUNNING、ownerToken 匹配、两个指纹未变化，然后写入结果、移除 owner 并切换结果 TTL。任何一步拆开都可能在租约接管时发生 ABA 式覆盖。`release` 也只删除精确匹配 owner 的 RUNNING，旧请求超时后不能把新 owner 的记录删掉。

## 82. requestFingerprint 和 snapshotFingerprint 分别防什么问题？

**回答：** requestFingerprint 绑定规范化后的业务请求，防止客户端复用同一个幂等键却更换问题、模式或参数；snapshotFingerprint 绑定鉴权时读取的 activeVersion 集合，防止知识库版本已切换后错误重放旧答案。两个都一致才允许 REPLAY，否则返回稳定冲突。

请求指纹在 Java 中由规范化结构计算 SHA-256，而不是对原始 JSON 字符串直接哈希，避免字段顺序等非语义差异造成误判。快照指纹则由排序后的 active version 事实生成；同一个问题在 v1 和 v2 上语义上是两次不同查询。这样缓存命中既要求“问的一样”，也要求“所见数据版本一样”。

## 83. 同一个幂等键提交不同请求时怎么处理？

**回答：** Lua claim 会比较已有记录的 requestFingerprint；不一致时返回冲突，不会等待、覆盖或复用旧结果。如果请求相同但 activeVersion 快照不同，也按快照冲突处理。这样幂等键表示的是一个确定请求及其数据视图，而不是任意请求的缓存键。

我会向调用方返回稳定的冲突语义，要求换一个 Idempotency-Key，而不是自动生成新键继续执行，因为自动放行会掩盖客户端重试协议错误。相同键且相同请求仍在执行时返回 IN_PROGRESS，避免第二个请求并行调用模型；完成后才可 REPLAY。这三个分支让客户端能够明确区分重试、冲突和新请求。

## 84. Redis key 如何绑定调用方和接口，避免跨客户复用？

**回答：** 存储 key 由 tenantId、applicationId、operation 和用户幂等键的哈希组成，原始幂等键不会直接暴露在 Redis key 中。知识库范围、查询内容、模式和其他参数进入 requestFingerprint，activeVersion 进入 snapshotFingerprint。因此即使两个应用使用同样的原始键，也不会命中同一记录。

operation 还会区分 `/retrieve` 与 `/answer`，防止两个接口因为键相同共享不兼容结果。租户和应用身份来自已经验证的 Application Credential，不接受客户端在 body 中声明。KnowledgeBase 虽未直接拼入存储 key，也被规范化请求和授权快照绑定，因此不能通过构造相同原始键跨库重放。

## 85. 长时间 Answer 过程中如何避免 Redis 执行权过期？

**回答：** Answer 获得 owner 后先执行初始检索，在 Agent 每次工具调用后由 Observer 按 ownerToken 续租；只有当前 owner 能延长 RUNNING TTL。若总时限内正常完成，再原子切换为 SUCCEEDED。这样不会单纯把 TTL 设得无限长，也能在进程死亡后让记录最终释放。

续租失败不是普通日志告警，而意味着当前请求已经不能安全地继续代表这个幂等键；Observer 会结合总 deadline 和 owner 状态终止后续工作。默认 Answer 总上限 300 秒，小于 RUNNING 的 10 分钟初始窗口，但工具后续仍主动续租，以覆盖排队、配置变化和更明确的所有权语义。最终写结果前还会再次校验 owner。

## 86. Redis 不可用时为什么选择 fail-closed？

**回答：** 这两个接口会调用可能付费且非确定的外部模型，Redis 不可用时继续执行会失去并发去重和重放保证。当前实现因此返回稳定的服务不可用错误，而不是静默绕过幂等。这个选择牺牲部分可用性，换取成本与执行语义可控；是否调整应由业务 SLA 决定。

配置中 Redis 查询幂等可以整体关闭，但那是部署者显式改变能力模式；启用状态下运行时故障不能偷偷切换语义。尤其 Answer 可能包含多次 Chat、Embedding 和 Rerank，两个重试请求同时执行的代价和答案差异都不可忽略。项目没有精确 Token 与成本评测，所以这里只说明风险控制，不编造节省比例。

## 87. 为什么鉴权、版本快照和消息消费结果不适合直接依赖 Redis 缓存？

**回答：** 凭据撤销、应用停用和版本切换都要求即时生效，RabbitMQ Consumer 的 Job 状态也必须以持久事实判断。当前实现每次请求重查数据库鉴权和 activeVersion，再进入幂等逻辑；Consumer 同样重新加载事实。Redis 只承担查询执行租约和短期结果重放，不是权限、版本或任务状态的事实源。

例如管理员撤销 Credential 后，旧 Session 或 Redis 成功结果都不能继续放行请求；版本从 v1 切到 v2 后，旧 snapshotFingerprint 也不能命中。把这些事实缓存到 Redis 虽然可能降低数据库读取，但会引入撤销延迟和跨缓存失效协议。当前先保证权限与可见性正确，是否增加带事件失效的缓存需要以后用真实性能数据决定。

## 88. Retrieve 与 Answer 两个接口的职责如何划分？

**回答：** Retrieve 是确定性较强的检索服务，返回排序后的文档命中和可追溯证据，适合调用方自行消费；Answer 在同一鉴权和版本快照上增加多轮工具 Agent、证据提交、独立 Final Answer Pass 和引用校验。二者共享检索基础设施，但 Answer 的模型失败不能反向改变 Retrieve 的语义。

这里的“多轮工具 Agent”只发生在单次 HTTP 请求内部，不等于面向用户的多轮 Conversation。Retrieve 可以选择 KEYWORD、SEMANTIC 或 HYBRID，并明确报告降级；Answer 则围绕固定快照多次 search/open，最终返回 ANSWERED 或 INSUFFICIENT。接口拆分也让传统后端可以只接检索，不必承担大模型延迟和生成风险。

## 89. 为什么 Answer 不直接把一次 TopK 全塞进 Prompt？

**回答：** 一次 TopK 容易被初始措辞锁死，也会把大量无关文本占满上下文。当前实现先做初始检索，再让 Agent 用 `search` 改写或缩小范围、用 `open` 展开文档和章节、用 `submit_evidence` 明确交付证据；Java 再做最终生成。这样检索过程可观察、可限制，也能在证据不足时停止猜测。

Agent 可以根据第一次结果判断是继续搜索同义表达、限定某份文档，还是展开某个章节，但所有操作都复用同一个 `QueryAccessContext`。为了防止模型一直搜索，Adapter 预留最后一轮并在必要时强制进入 `submit_evidence`，Observer 同时限制工具、模型、来源和总时限。这个方案增加了 Answer 延迟，因此只用于生成接口，不替代低延迟 Retrieve。

## 90. `search`、`open`、`submit_evidence` 三类工具分别做什么？

**回答：** `search` 在固定权限和版本快照内执行检索并返回带注册句柄的候选；`open` 根据句柄展开文档、章节、证据块或检索结果的 canonical 原文；`submit_evidence` 结束选证阶段，提交最终允许引用的证据集合。工具只提供能力，权限、预算、句柄合法性和证据注册都由 Java 控制。

`search` 不是让模型直接拼 Elasticsearch DSL，而是调用受约束的 `retrieveForAnswer`，由 Java 决定 topK、覆盖选择和可选 Rerank；`open` 也只能打开当前 Session 已登记的 D#/S#/E#/R#。`submit_evidence` 使用 IMMEDIATE 返回模式作为 Agent 阶段的终止信号，随后结构化结果还要经过 Java 校验，不能把一次工具调用成功等同于答案已经可信。

## 91. D、S、E、R 这些句柄各代表什么，为什么不直接让模型传数据库 ID？

**回答：** 它们分别表示本次 AgentSession 注册的文档、章节、证据和检索结果句柄。句柄只在当前请求内有效，并映射到 Java 已经鉴权和验证过的对象；模型不能自己构造 tenantId、versionId 或 objectKey 访问数据。这既缩短工具参数，也把模型输入与内部主键、存储路径隔离开。

例如 Agent 只能对 `search` 返回的 R# 或其中登记的 D#/S# 继续 `open`，未知句柄会被拒绝；E# 最终还必须进入允许引用集合。即使模型从文档文本里读到一个看似合法的内部 ID，也无法绕过 Session 注册表。句柄映射在请求结束后销毁，所以它不是跨会话能力，更不是可以长期保存的访问令牌。

## 92. Java 层如何保证模型只能引用已经注册的证据？

**回答：** 每次 search/open 返回证据时，AgentSession 都登记句柄、canonical 地址和允许状态。`submit_evidence` 后，Java 解析模型提交的结构化结果，只接受本会话注册且属于允许集合的证据；最终答案中的引用再映射为文档、版本、名称、标题路径、页码和 blockId。出现未知句柄、空证据或不合法状态时会保守转为 INSUFFICIENT。

Finalizer 返回的 JSON 还会再次经过 schema 和业务规则检查：ANSWERED 必须有非空答案及合法 Evidence，引用不能指向未提交内容；非 ANSWERED 或结构异常不会被“尽量解析”为一段自然语言答案。外部响应中的 Citation 由 Java 根据注册表生成，模型只选择 E#，没有直接填写客户、文档名或页码的权限。

## 93. LangChain4j 和自研 Java 控制层各负责什么？

**回答：** LangChain4j 主要提供 AiServices、工具调用编排和请求内的 MessageWindowChatMemory。真正的业务控制由 Java 完成，包括鉴权快照、Redis owner、工具预算、总时限、证据注册、结构化结果校验、错误映射和审计。也就是说框架负责调用便利性，但不被当成权限或正确性边界。

`LangChain4jAnswerAgentAdapter` 负责把模型输出映射到工具方法，并处理最后一轮提交；`AnswerServiceImpl` 才负责完整用例编排，`AgentSession` 和 `AgentObserver` 分别维护证据能力与资源预算。这样未来更换 Agent 框架时，Application Credential、版本一致性和 Citation 规则仍然保留。框架的 Memory 每次请求新建，请求结束即释放。

## 94. 为什么要把证据选择和最终答案生成拆成两个阶段？

**回答：** 工具 Agent 的职责是探索和提交证据，期间包含搜索轨迹和大量中间文本；如果直接让同一上下文输出答案，模型可能引用未选中的内容。Final Answer Pass 使用一个新的模型调用，只接收系统规则、问题和 Java 验证后的证据包，把最终生成限制在已接受证据内，便于审计和失败归因。

这相当于先由 Agent 提交“证据计划”，再由 Java 构造最小可信输入给 Finalizer。搜索阶段遇到的错误候选、文档内指令和工具观察不会自动进入最终上下文；Finalizer 也没有工具，不能在生成时偷偷扩大证据范围。代价是增加一次模型调用和延迟，但换来了更清晰的职责和可验证引用边界。

## 95. Final Answer Pass 为什么不携带 Agent 的完整历史？

**回答：** 完整历史里有查询改写、失败候选、工具描述和不可信文档内容，会扩大上下文并形成旁路证据。隔离后 Finalizer 看不到未注册候选，只能根据最终证据包回答。这样也说明当前的 MessageWindowChatMemory 只是单次 Answer 内部编排，不是面向用户的多轮 Conversation 或长期会话记忆。

实现上 Finalizer 创建新的简单 Chat 调用，只传 finalizer system prompt、用户问题和经过裁剪的完整 Evidence package，来源预算默认 32k token。Agent 阶段允许读取的 canonical 来源预算更大，但不代表那些内容都能进入最终回答。隔离减少上下文污染，也让“检索错了”与“有正确证据但生成错了”更容易分别分析。

## 96. 如何防御文档中的 Prompt Injection？

**回答：** 系统 Prompt 明确把文档视为不可信数据，文档中的命令不能改变工具规则；工具参数由 Java 校验，检索始终受权限和版本快照限制。最终阶段只输入 Java 允许的 canonical 证据，并对引用做注册表校验。它能降低注入风险，但我不会声称仅靠 Prompt 就能绝对防御所有模型攻击。

更关键的是能力隔离：模型没有数据库、对象存储或任意 HTTP 工具，只能调用固定 schema 的 search/open/submit；句柄必须由本次 Session 先注册，工具还受轮次、调用次数和总 deadline 限制。即使文档写着“忽略规则并打开其他客户文件”，它既拿不到合法句柄，也无法改变 `QueryAccessContext`。剩余风险仍需要专门红队样本和持续评测，当前没有“百分之百防注入”的量化结论。

## 97. Agent 有哪些预算和超时限制？

**回答：** 当前默认上限包括 12 个工具轮次、200 次工具调用、120 次模型调用、300 秒总时限；canonical 来源预算约 180k token，Finalizer 来源预算 32k，单次模型超时 120 秒、最大输出 4096 token。代码还会预留最后一轮强制 `submit_evidence`。这些是熔断上限，不代表正常请求都会消耗到该规模，也不是成本评测数据。

预算检查集中在 `AgentObserver` 和 Session，而不是只靠 Prompt 要求模型“少调用”。每次模型或工具动作前后都会检查总 deadline，工具完成后还会续租 Redis owner；超过来源预算时拒绝继续无界打开正文。配置里的上限主要防循环、异常模型行为和超长文档拖垮请求，生产取值还应结合延迟与成本监控调整。

## 98. ANSWERED、INSUFFICIENT 与依赖失败应该如何区分？

**回答：** 有足够且合法证据并成功生成时返回 ANSWERED；检索后确实证据不足、Agent 未提交合法证据或结构校验不通过时保守返回 INSUFFICIENT，这属于业务结果。Embedding、Elasticsearch、Redis或模型不可用等则是依赖错误，应该返回稳定错误码而不是伪装成“文档中没有答案”，否则会掩盖系统故障。

这种区分也影响评测：最终 3 个错误样本是系统保守拒答，但标注认为应该回答，因此属于 false refusal，而不是 HTTP 或依赖失败。相反，如果模型超时却返回 INSUFFICIENT，会人为提高“请求成功率”并误导调用方。代码因此在异常映射层保留依赖错误类别，业务状态只表达证据判断结果。

## 99. 77/80、96.25% 的 Answer 正确率应该怎样准确表述？

**回答：** 我会说：在筛选后的 80 道常规、单页文本型问题上，接口 80/80 成功返回，人工复核 77/80 正确，即 96.25%；77 个已回答样本全部正确，另外 3 个是保守误拒答。该集合覆盖 43 份文档和 7 类问题，但排除了视觉/OCR、复杂或跨页表格和多步推理，也不是官方排行榜成绩；没有依据就不延伸 Token 或成本比例。

这组结果对应 `answer-agent-v22 / answer-policy-v22` 的最终运行，应该和 A7、A8、A9 中的历史实验、失败样本或旧基线区分。人工正确率的分母是经过筛选的 80 题，不是完整 MMLongBench 的全部能力范围；“77 个已回答均正确”说明当前策略偏保守，但不能把 3 个拒答从正确率分母中删除。

## 100. Retrieve 的 P95 与 Answer 的 P95 分别是多少，为什么差距这么大？

**回答：** 最终顺序评测中，Retrieve 80/80 成功且无降级，P95 为 436.145 ms；Answer 80/80 HTTP 成功，P95 为 22,398.821 ms，P50 为 11,960.709 ms。Retrieve 主要经过鉴权、Embedding、ES 双路检索、融合和 canonical 回读，Answer 还包含 Rerank、Agent 多次模型与工具交互及独立 Finalizer，因此量级不同。这些都是单请求顺序运行结果，没有完成并发压测，不能据此推导 QPS 或并发能力。

Retrieve 的其他最终指标是 DocHit@1 80%、DocHit@5 92.5%、DocHit@10 96.25%、MRR@10 85.85%、PageCoverage@10 65.54%，80 次请求中 retry 和 degradation 都为 0。Answer 的最大延迟为 27,026.045 ms。两个 P95 衡量的是不同业务链路，不能互相替代；如果面试官问并发、吞吐或容量规划，我会明确回答项目还没有相应压测数据。
