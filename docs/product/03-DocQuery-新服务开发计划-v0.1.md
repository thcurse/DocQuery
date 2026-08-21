# DocQuery 新服务开发计划 v0.1

> 开发方式：Greenfield（全新服务）  
> 文档状态：N0—N4.4 均已实现、验证并经用户验收；N4.4 已关闭  
> 产品基线：[DocQuery 产品设计 v0.1](./01-DocQuery-产品设计-v0.1.md)  
> 页面基线：[DocQuery 管理后台页面说明 v0.1](./02-DocQuery-管理后台页面说明-v0.1.md)  
> 检索与异步处理基线：[文档检索与异步处理技术选型](../technical-decisions/02-文档检索与异步处理技术选型.md)

---

## 1. 开发结论

DocQuery v0.1 按一个全新的 B2B 知识库服务开发，不在现有工程上渐进重构。

新服务的设计只从已经确认的产品模型出发：

```text
Tenant
├─ AdminUser
├─ Application
│  └─ Credential
├─ KnowledgeBase
│  └─ Document
│     └─ DocumentVersion
├─ ApplicationGrant
├─ ProcessingJob
└─ AuditLog
```

旧工程中的 `User`、`OrganizationTag`、`fileMd5`、旧接口和旧数据库结构，都不作为新服务的兼容目标。

---

## 2. 为什么必须新建

现有工程最初表达的是“用户上传文件并按组织标签检索”，新产品表达的是“传统业务应用通过凭证访问被授权的知识库”。两者的身份、权限和资源模型不同。

如果从旧工程逐步修改，容易出现以下问题：

- 把传统业务终端用户误建成 DocQuery 用户
- 把组织标签继续当成知识库权限
- 把文件哈希继续当作文档身份
- 为旧接口保留大量兼容分支
- 新设计不断迁就旧数据库
- 为了复用代码而复用错误边界

因此，新服务不承担旧数据迁移、旧接口兼容或旧前端适配。

---

## 3. 旧工程的定位

旧工程只是一份只读参考材料，可用于了解：

- 哪些文档格式曾经解析成功
- 旧切片算法曾经暴露了哪些问题
- Embedding 和 Elasticsearch 的基础连接经验
- 对象存储、消息队列等依赖的本地运行经验
- 哪些失败场景已经踩过坑

以下内容不得直接复制到新服务的核心链路：

- 旧领域实体和数据库表
- 旧 Controller 和响应结构
- 用户、组织标签和公开文件权限逻辑
- 以 `fileMd5` 为核心的存储路径与消息
- 携带预签名 URL 的任务消息
- 旧配置文件中的密钥和环境假设
- 与旧前端耦合的接口行为

算法代码也不是默认复用。只有当它满足新接口、具备测试并且没有旧领域依赖时，才允许以独立适配器的形式迁入。

---

## 4. 代码与仓库隔离

### 4.1 推荐方式

新服务使用独立仓库，不嵌套在旧工程中：

```text
DocQuery-Legacy/       旧工程，只读参考
docquery-platform/     新服务，独立 Git 仓库
docquery-demo/         可选，传统工单系统接入演示
```

如果暂时不能创建多个仓库，至少也应使用一个全新的顶层目录和独立构建文件，不能引用旧工程的源码包。

### 4.2 新仓库的第一条规则

新项目第一次提交只包含：

- 项目说明
- 构建配置
- 代码规范
- 空模块或包结构
- 测试框架
- 本地依赖编排
- 配置模板
- 数据库迁移机制

第一次提交不搬运任何旧业务代码。

---

## 5. 产品完成后的交付效果

MVP 必须完整演示：

```text
平台管理员创建租户
→ 租户管理员创建业务应用
→ 为应用生成凭证
→ 创建知识库
→ 授予应用对知识库的 READ 权限
→ 上传一份文档
→ 文档由“处理中”变为“可检索”
→ 传统业务后端携带应用凭证查询指定知识库
→ DocQuery 返回带文档、版本和位置引用的结果
→ 管理后台显示本次应用访问审计
```

并且证明：

- 未授权应用不能访问知识库
- 应用不能跨租户访问
- 调用方不能通过伪造 `tenantId` 越权
- 新版本失败时旧版本继续可用
- 删除开始后文档立即停止参与新查询
- 重复消息不会产生重复版本或重复索引
- Worker 故障后任务可以恢复

---

## 6. 产品责任边界

### 6.1 DocQuery 负责

- 平台和租户管理身份
- 租户隔离
- 应用和应用凭证
- 知识库
- 应用对知识库的授权
- 文档、版本和处理任务
- 带引用检索
- 应用级访问审计

### 6.2 传统业务系统负责

- 自己的终端用户登录
- 用户、角色、部门和业务数据权限
- 判断当前业务用户是否可以发起某次查询
- 在业务页面中展示检索结果

### 6.3 MVP 不做

- 业务用户、部门或用户组 ACL
- 文档级和段落级 ACL
- 多知识库自动路由
- 通用 Agent 工作流
- 在线计费
- 正式 SDK
- 旧系统数据迁移
- 旧 API 兼容层

---

## 7. 新服务结构

MVP 使用一个传统的 Spring Boot 单体工程。第一版只有一个 `pom.xml`、一个启动类和一个进程，按照常见的分层结构组织代码：

```text
docquery-platform
├─ pom.xml
└─ src
   ├─ main
   │  ├─ java/com/docquery
   │  │  ├─ DocQueryApplication.java
   │  │  ├─ controller       HTTP 接口
   │  │  ├─ service          业务规则和事务
   │  │  │  └─ impl
   │  │  ├─ mapper           MyBatis 数据访问
   │  │  ├─ entity           数据库实体
   │  │  ├─ dto              接口请求和数据库查询条件
   │  │  ├─ vo               接口响应
   │  │  ├─ security         管理员认证、应用凭证认证
   │  │  ├─ mq
   │  │  │  ├─ producer
   │  │  │  ├─ consumer
   │  │  │  └─ message
   │  │  ├─ parser           PDF、DOCX、TXT、Markdown 解析
   │  │  ├─ retrieval        关键词、向量导航、RRF 和原文读取
   │  │  ├─ ai               Chat、Embedding 和受控工具循环
   │  │  ├─ cache            Redis 请求幂等
   │  │  ├─ client           S3兼容对象存储、ES等外部调用
   │  │  ├─ job              Outbox 发布和补偿任务
   │  │  ├─ config
   │  │  ├─ exception
   │  │  └─ common
   │  └─ resources
   │     ├─ application.yml
   │     └─ db/migration
   └─ test
      └─ java/com/docquery
```

各层职责保持简单：

- `Controller` 只负责参数绑定、调用 Service 和返回 VO
- `DTO` 使用普通 class 承载请求参数或数据库查询条件，基础格式使用 Jakarta Validation
- `VO` 使用普通 class 承载接口响应，不作为数据库写入对象
- `Service` 接口定义业务能力，`service.impl` 负责产品规则、授权判断和事务边界
- `Mapper` 只负责 MyBatis 数据库访问
- `Entity` 表达持久化数据，不直接返回给外部
- `Security` 分开处理管理员身份和应用凭证
- `MQ` 负责异步文档处理，不承载权限模型
- `Parser` 只负责从文件得到标准化原文、真实标题和位置
- `Retrieval` 负责 ES 双路查询、排名融合和原文地址
- `AI` 通过自有 Gateway 封装 LangChain4j，不让业务层依赖模型供应商
- `Cache` 只负责服务请求幂等和短期结果复用
- `Client` 统一封装 S3 兼容对象存储和 Elasticsearch

RabbitMQ Consumer 第一版就在同一个 Spring Boot 应用中运行。只有后续压测证明文档处理会明显抢占查询资源，或者确实需要独立扩容时，才考虑拆出 Worker；这不是 MVP 前置设计。

第一版不引入复杂的领域驱动分层、端口适配器、多 Maven 模块或微服务。先把传统三层 Spring Boot 项目写清楚、测清楚。

---

## 8. 核心工程原则

### 8.1 数据库是业务事实源

租户、授权、文档版本、处理任务、Outbox 和生效状态以 MySQL 为准。S3 兼容对象存储保存原始文件和标准化原文，默认自托管实现为 SeaweedFS；Elasticsearch、Redis 和 RabbitMQ 都是外部投影或传递组件，不承担最终业务事实。

### 8.2 所有资源使用稳定 ID

- `tenantId`
- `applicationId`
- `credentialId`
- `knowledgeBaseId`
- `documentId`
- `documentVersionId`
- `jobId`

内容哈希只用于去重和完整性校验，不能替代资源 ID。

### 8.3 身份来自可信凭证

服务请求通过应用凭证得到可信的 `applicationId` 和 `tenantId`。调用方传入的租户字段不能改变授权上下文。

### 8.4 授权先于检索

先验证应用是否拥有目标知识库的 `READ` 授权，再执行检索。不能全局召回后再删除无权限结果。

### 8.5 默认按至少一次投递设计

RabbitMQ 消息可能重复，Consumer 可能在任意阶段崩溃。Transactional Outbox 保证已提交事件最终可以发布，但不保证消息绝不重复；所有处理步骤必须可重试并且业务结果幂等。

### 8.6 检索卡不是答案证据

向量只索引 DocumentProfile 和真实章节 RetrievalNode，用于返回原文地址。关键词检索真实原文；受控 Agent 必须通过只读工具读取原文后才能生成答案和引用。

### 8.7 Redis 不承载业务正确性

Redis 第一版只保存 Retrieve/Answer 请求的短期幂等状态和结果。Grant、Credential、activeVersion、文档处理状态和 RabbitMQ 消费幂等仍然以 MySQL 为准。

### 8.6 外部契约后置稳定

开发期间仍需要明确的内部 DTO 和接口测试，但在 MVP 行为完成前，不承诺路径和字段长期兼容。产品链路稳定后，再从真实实现整理公开契约。

---

## 9. 里程碑总览

| 里程碑 | 产品结果 | 工程结果 | 完成标志 |
| --- | --- | --- | --- |
| N0 项目初始化 | 新服务可以独立启动 | 新仓库、测试、数据库版本管理、依赖编排 | 不依赖旧工程 |
| N1 身份与授权 | 能创建应用、知识库和授权 | Tenant、Application、Credential、Grant | 授权矩阵测试通过 |
| N2 文档入库 | 文档最终变为“可检索” | Document、Version、Job、Outbox、S3兼容对象存储、RabbitMQ、检索地图和双索引投影 | 重复投递结果一致 |
| N3 授权检索 | 业务应用获得原文证据或单轮答案 | 前置鉴权、Redis 幂等、ES 双路检索、RRF、受控工具、引用和审计 | 检索、问答与越权测试通过 |
| N4 生命周期与后台 | 管理员完成全部 P0 操作 | 更新、删除、重试和管理页面 | 后台产品闭环成立 |
| N5 工程验证 | 故障可恢复、性能可量化 | 指标、压测、故障注入 | 有可复现报告 |
| N6 对外交付 | 传统系统可以按文档接入 | 契约、Demo、部署指南 | 新环境可完成演示 |

---

## 10. N0：全新项目初始化

### 10.1 目标

建立一个没有旧业务概念的新服务骨架。

### 10.2 开发内容

- 创建独立 Git 仓库
- 选择 Java 和 Spring Boot 基线版本
- 建立传统 Spring Boot 分层目录
- 保持一个 `pom.xml`、一个启动类和一个运行进程
- 配置单元测试和集成测试
- 建立面向全新空库的数据库版本管理机制
- 提供本地依赖编排
- 提供不含秘密的配置模板
- 建立统一错误、请求追踪和日志脱敏规则
- 建立持续集成入口

### 10.3 禁止项

- 不复制旧 `pom.xml`
- 不复制旧 `application.yml`
- 不复制旧实体和 Controller
- 不连接旧数据库
- 不使用旧表名
- 不把旧项目包名直接沿用为新领域结构

### 10.4 验收

- 新仓库可以独立构建和测试
- 新环境可以从空库启动
- 所有外部依赖可通过配置替换
- 仓库不包含真实密钥
- 源码中不存在 `OrganizationTag`、`isPublic` 或旧权限概念

---

## 11. N1：身份、应用与知识库授权

### 11.1 目标

让系统先正确认识“谁在调用”和“可以访问哪个知识库”。

### 11.2 最小对象

```text
Tenant
├─ AdminUser
├─ Application
│  └─ Credential
├─ KnowledgeBase
└─ ApplicationGrant
```

### 11.3 关键约束

- `application(tenant_id, code)` 唯一
- `knowledge_base(tenant_id, name)` 唯一
- `credential.key_id` 全局唯一
- 数据库只保存 Secret 哈希
- `application_grant(application_id, knowledge_base_id)` 唯一
- Grant 两端必须属于同一租户
- Grant 权限使用字符串代码：`"1"` = `READ`、`"2"` = `WRITE`、`"3"` = `READ_WRITE`
- 状态和管理员角色同样使用文档化的 `VARCHAR` 字符串数字代码
- 六张 N1 表的每一个字段都有数据库备注，枚举字段备注列出代码含义
- 停用租户、应用或知识库后，对应访问立即失效

### 11.4 凭证行为

- Secret 创建时只展示一次
- 支持同一应用短期并存两把凭证
- 支持凭证撤销和轮换
- 请求通过凭证解析出应用和租户
- 管理员登录 Session 与应用凭证完全分离

### 11.5 验收

- 一个租户可以拥有多个应用和知识库
- 可以授予、修改和撤销 `READ`、`WRITE`、`READ_WRITE`
- 跨租户 Grant 无法创建
- 数据库泄露不能直接得到 Secret 明文
- A 应用不能使用 B 应用的授权
- 撤销凭证后新请求立即失败

---

## 12. N2：可靠文档入库

### 12.1 目标

将一份文档可靠地处理成指定知识库中的可检索版本。

### 12.2 对象关系

```text
KnowledgeBase
└─ Document
   ├─ activeVersionId
   └─ DocumentVersion
      └─ ProcessingJob
```

还需要 `OutboxEvent` 保证数据库事实和异步消息可靠衔接。

### 12.3 外部文档状态

- 处理中（`PROCESSING`）
- 可检索（`READY`）
- 处理失败（`FAILED`）
- 删除中（`DELETING`）
- 已删除（`DELETED`）

其中，“可检索（`READY`）”表示处理已经完成并且该版本可以参与查询。

### 12.4 处理链路

```text
接收上传
→ 文件写入S3兼容对象存储
→ 数据库事务创建版本、任务和 Outbox
→ 发布稳定的 documentVersionId
→ RabbitMQ Consumer 读取对象并解析
→ 生成标准化原文、真实标题树和位置映射
→ 生成 DocumentProfile 和真实章节 RetrievalNode
→ 原始正文写入 Elasticsearch 关键词索引
→ 检索卡生成导航向量并写入 Elasticsearch
→ 校验关键词和向量两个索引投影
→ 原子切换 activeVersionId
→ 版本变为“可检索（READY）”
```

消息只携带稳定 ID 和追踪信息，不携带预签名 URL。

第一版支持文本型 PDF、DOCX、TXT 和 Markdown。扫描 PDF、OCR、图片语义、复杂版面恢复和模型生成虚拟目录不属于 N2 范围。

文档结构只使用真实标题。很长但没有子标题的章节仍然保留为一个 RetrievalNode；关键词索引通过物理页、段落或行位置定位原文。为生成检索卡而进行的临时模型输入批次不写入数据库或 ES，也不成为检索节点。

### 12.5 幂等规则

- 上传操作支持幂等键
- `(document_id, version_no)` 唯一
- ES 原文位置记录和检索节点 ID 由版本 ID、真实结构或稳定位置确定性生成
- 每个处理阶段记录完成事实
- 重复消息不能重复创建版本、检索节点或 ES 投影
- 一个文档任意时刻最多有一个生效版本
- RabbitMQ 消费幂等由 MySQL Job 状态和稳定 ID 保证，不使用 Redis 锁

### 12.6 验收

- 重复投递不会增加 ES 原文位置记录或检索节点数量
- Consumer 崩溃后能够安全重试
- RabbitMQ 不可用时 PENDING Outbox 不会丢失，恢复后继续发布
- RabbitMQ 已收到消息但 Outbox 尚未标记 SENT 时允许重复发布，消费者结果仍然幂等
- 只生成真实标题节点，不出现模型虚构目录
- 关键词索引或导航向量索引任一未完成时，新版本不能 READY
- v2 处理中或失败时 v1 继续可用
- v2 成功后新查询只使用 v2
- 不同租户上传相同内容不会相互覆盖

### 12.7 N2 子阶段与确认门

N2 不一次性完成，按以下顺序逐段确认：

| 子阶段 | 只完成什么 | 本阶段明确不做 | 完成证据 |
| --- | --- | --- | --- |
| N2.1 | Document、DocumentVersion、ProcessingJob、OutboxEvent 数据模型和管理面上传受理契约 | 不接 MQ、不解析、不建 ES 索引 | 迁移、Service 和生命周期集成测试 |
| N2.2 | SeaweedFS 原文件保存、Outbox Publisher、RabbitMQ 发布消费、重试和死信骨架 | 不生成检索卡、不切换 READY | 对象存储、RabbitMQ 中断恢复、重复消息和 Outbox 测试 |
| N2.3 | PDF、DOCX、TXT、Markdown 解析，生成标准化原文、真实标题树和位置映射 | 不生成虚拟目录、不调用模型 | 格式样本、位置引用和不支持文件测试 |
| N2.4 | LangChain4j Gateway、结构化 DocumentProfile/RetrievalNode、单一 retrieval JSONL 和导航 Embedding | 不实现 ES 或查询 Agent | JSON Output + 本地 Schema、真实标题覆盖、2,560 维向量、模型失败和 artifact 幂等测试 |
| N2.5 | ES 关键词与导航向量双索引、投影校验和 activeVersion 原子切换 | 不实现对外 Retrieve/Answer | 重复投影、部分失败、旧版本继续服务和 READY 端到端测试 |

每个子阶段开始前必须在 `docs/development` 中写清对象、接口、状态、错误和测试；得到明确开始确认后才修改代码。完成后提供测试证据并等待验收。

N2.1 已按本表范围完成实现和自动化验证，并于 2026-08-02 通过用户完成验收；该状态不授权直接实现 N2.2。

N2.2、N2.3 已按本表范围完成实现、验证并经用户验收。N2.4 已于 2026-08-10 经用户确认完成；N2.5 已于 2026-08-11 按确认设计完成实现和验证并经用户确认完成，详见 [`N2.5 双索引投影与版本激活`](../development/N2.5-双索引投影与版本激活.md)。

---

## 13. N3：应用授权检索、原文阅读与单轮问答

### 13.1 目标

传统业务后端携带应用凭证，查询一个明确指定且已经授权的知识库，获得原文证据，或让受控 Agent 基于这些证据生成单轮答案。

### 13.2 固定顺序

```text
校验应用凭证
→ 得到可信 tenantId 和 applicationId
→ 读取请求指定的 knowledgeBaseId
→ 检查知识库属于该租户并已启用
→ 检查 ApplicationGrant(READ)
→ 用一条 MySQL 查询固定该知识库当前 activeVersion 快照
→ 使用 tenantId、applicationId、接口类型和 Idempotency-Key 竞争 Redis 查询执行权
→ 使用 tenantId、knowledgeBaseId 和生效版本并行过滤 BM25 与 KNN
→ Java 使用 RRF 融合关键词和检索卡向量排名
→ Retrieve 返回原文证据和引用
→ Answer 通过受控工具继续搜索、查看真实目录和读取原文
→ 证据充分后生成单轮答案和引用
→ 记录应用访问审计
→ Redis 在短 TTL 内复用相同幂等请求结果
```

BM25 搜索真实原文，KNN 搜索 DocumentProfile 和真实章节 RetrievalNode。两者使用相同数据范围，权限和 activeVersion 条件必须进入查询过滤器；不能全局召回后删除无权限结果。

Redis 不缓存 Grant、Credential 或 activeVersion。相同应用、相同 Idempotency-Key、相同请求在 TTL 内复用执行状态或结果；相同 Key 但请求体不同必须返回幂等冲突。

### 13.3 受控工具

第一版内部工具限定为：

- `searchDocuments`
- `getDocumentOutline`
- `searchWithinDocument`
- `readDocument`

Agent 最大轮数、候选数量、读取范围和上下文预算必须配置并测试。模型不能通过工具参数改变服务端确定的租户、应用、KnowledgeBase 和 activeVersion。

### 13.4 最小引用

- `documentId`
- 文档名称
- `documentVersionId`
- 版本号
- 页码、段落或标题路径
- 原文证据
- 关键词高亮和命中通道
- 相关性排名信息

检索卡摘要不能作为引用。

### 13.5 审计

至少记录：

- `requestId`
- `queryExecutionId`
- `tenantId`
- `applicationId`
- `credentialId`
- `knowledgeBaseId`
- 操作类型
- 成功或失败
- 失败分类
- 响应耗时
- 检索模式、降级状态和 Agent 工具轮数
- 发生时间

默认不保存完整问题、完整结果、Secret 或授权头。

### 13.6 验收

- 授权应用可以独立检索目标知识库并获得原文证据
- 授权应用可以获得只基于原文证据的单轮答案
- 未授权应用被拒绝并留下审计
- 跨租户访问被拒绝
- 伪造 `tenantId` 不影响授权结果
- 返回结果带可展示引用
- 相同应用、相同 Idempotency-Key、相同请求不会并发执行两次昂贵链路
- 相同 Key、不同请求被拒绝，不同应用不能复用结果
- 关键词单路、向量导航单路和 RRF 融合分别具有可复现评测结果
- Embedding 查询失败时明确降级为关键词模式，不声称完成双路检索
- Chat Model 失败时 Retrieve 仍可独立使用，Answer 返回生成失败
- “无相关结果”与“服务异常”可以区分

### 13.7 N3 子阶段与确认门

| 子阶段 | 只完成什么 | 本阶段明确不做 | 完成证据 |
| --- | --- | --- | --- |
| N3.1 | Retrieve 内部契约、应用凭证与 Grant 前置校验、activeVersion 快照、Redis Idempotency-Key | 不注册 Retrieve/Answer 路由，不查询 ES，不调用 Answer Agent | 越权、快照、并发相同 Key、Key 冲突和 TTL 测试 |
| N3.2 | BM25 与检索卡 KNN 并行查询、Java RRF、原文读取和引用返回 | 不做多轮工具循环 | 三种检索模式、范围过滤、降级和引用测试 |
| N3.3 | `searchDocuments`、`getDocumentOutline`、`searchWithinDocument`、`readDocument` 与受控单轮 Answer | 不做多轮聊天和通用 Agent | 工具边界、轮数预算、无证据和提示注入测试 |
| N3.4 | 应用访问审计、检索评测集和查询性能基线 | 不承诺无实测依据的指标 | 审计、Recall/MRR/nDCG、引用正确率和延迟报告 |

N3 每个子阶段同样需要开始确认和完成验收，不能因 N2 已完成而自动连续实现。

N3 总体设计已确认。N3.1 的内部查询授权、activeVersion 不可变快照和 Redis 原子幂等，N3.2 的 `/retrieve`、Query Embedding、BM25/KNN、Java RRF 和 canonical 原文证据，N3.3 的 `/answer`、四个只读工具、受控 Java Agent 循环和严格引用校验，以及 N3.4 的查询审计、评测执行器和确定性性能基线，均已实现、验证并经用户确认完成。`n3-eval-v1` 只保留为冒烟夹具，成熟公开数据集真实效果评测延期为发布前最终门禁。详见 [`N3.1 查询授权、版本快照与 Redis 幂等`](../development/N3.1-查询授权与Redis幂等.md)、[`N3.2 BM25、KNN、RRF 与原文引用`](../development/N3.2-BM25与KNN检索及原文引用.md)、[`N3.3 受控单轮 Answer 与只读工具`](../development/N3.3-受控单轮Answer与只读工具.md) 与 [`N3.4 查询审计、检索评测与性能基线`](../development/N3.4-查询审计与检索评测基线.md)。

---

## 14. N4：生命周期与管理后台

### 14.1 页面顺序

1. 登录与租户上下文
2. 租户概览
3. 应用列表、详情和凭证
4. 知识库列表和详情
5. 应用对知识库的授权
6. 文档上传、状态和版本历史
7. 失败任务详情和重试
8. 访问审计

### 14.2 生命周期规则

- 管理员显式选择创建文档或上传新版本
- 新版本成功后才替换旧版本
- 失败版本不能参与检索
- 删除开始时立即从检索可见集合移除
- 物理清理异步执行并允许补偿
- 凭证和授权撤销立即影响新请求

### 14.3 验收

- 管理员可以完成全部 P0 流程
- Secret 只完整展示一次
- 页面展示“可检索（`READY`）”等中文状态
- 新版本处理中时能看到旧版本仍在服务
- 失败原因和重试入口明确
- 页面没有业务用户、部门或用户组权限入口

### 14.4 当前子阶段状态

N4.1 已按确认范围实现文档列表/详情、版本历史、ProcessingJob 列表/详情和租户管理员人工重试。人工重试复用失败的同一 `DocumentVersion`，在 MySQL 事务内创建递增 attempt 与 Outbox，旧 `activeVersionId` 在新 attempt 成功前保持不变；管理请求幂等事实保存在 MySQL，不使用 Redis。真实 MySQL、SeaweedFS、RabbitMQ、Elasticsearch + Fake Gateway 端到端和默认全量回归均已通过。用户已于 2026-08-12 明确确认 N4.1 完成；该确认不授权自动进入后续子阶段。详见 [`N4.1 文档、版本、任务查询与失败重试`](../development/N4.1-文档版本任务查询与失败重试.md)。

N4.2 已按确认范围实现整个逻辑 Document 的不可恢复删除：删除受理事务立即将文档改为 `DELETING` 并清空 `activeVersionId`，独立 RabbitMQ 删除链路随后幂等清理全部版本的双索引投影、canonical/retrieval 和原文件，全部成功后进入 `DELETED` 并释放名称。删除任务、版本、处理任务、Outbox、查询审计和 Document 墓碑继续保留；失败文档保持不可检索并支持受控人工重试。真实 MySQL、SeaweedFS、RabbitMQ、Elasticsearch + Fake Gateway 端到端和默认 142 项全量回归均已通过。用户已于 2026-08-12 明确确认 N4.2 完成；该确认不授权自动进入后续子阶段。详见 [`N4.2 文档安全删除与异步清理`](../development/N4.2-文档安全删除与异步清理.md)。

N4.3 已按冻结范围实现同源管理后台：平台管理员可完成 Tenant 列表、创建、详情、更新和启停；租户管理员可完成 Application、Credential、Grant 和 KnowledgeBase 的核心管理。前端使用 React + TypeScript + Vite、React Router Data Mode、TanStack Query 和 Ant Design，沿用后端 Session、CSRF、角色与租户隔离，并作为静态资源打入同一个 Spring Boot JAR。默认 `clean verify` 中 144 项 Java 测试为 142 项执行通过、2 项按设计跳过，前端 Vitest 8/8；真实 Spring Boot JAR + 临时 MySQL + Edge 的 Playwright 核心流程 1/1 通过。用户已于 2026-08-15 明确确认 N4.3 完成，本阶段验收关闭；该确认不授权自动进入 N4.4、N5 或其他阶段。详见 [`N4.3 管理后台前端基础与核心资源管理`](../development/N4.3-管理后台前端基础与核心资源管理.md)。

N4.4 原冻结范围及租户管理员管理补充均已实现：管理后台覆盖文档上传、版本/任务/删除生命周期、真实 Credential + Grant 的 Retrieve/Answer 调试、最小化查询审计和平台/租户管理员共用的“使用说明”；Application、KnowledgeBase 和 Document 提供稳定 ID 复制入口，OpenAPI YAML 与 Postman Collection 随同源 JAR 提供下载。平台管理员还可在 Tenant 详情列出、新增、启停和重置租户管理员密码，并保护最后一个有效管理员。默认 `clean verify` 中 148 项 Java 测试为 146 项执行通过、2 项按设计跳过，前端 Vitest 13/13；完整端到端链路及租户管理员补充流程均通过真实浏览器验证。本阶段未新增数据库迁移，未修改 `pom.xml` 或 `compose.yaml`。用户已于 2026-08-15 明确确认 N4.4 完成，阶段验收关闭；该确认不授权自动进入正式公开数据集评测或多轮 Agent。详见 [`N4.4 完整展示流程与外部 API 交付`](../development/N4.4-完整展示流程与外部API交付.md)。

---

## 15. N5：可靠性、效果和性能证据

### 15.0 当前最小分阶段

- N5.1 基础适配：冻结 100 份上游真实 PDF、80 个问题、数据映射、来源指纹和免费校验；P0 验证 DeepDoc 技术可行性，P1 完成四格式生产接入。
- N5.1-P2：在既有“测试科技”租户的隔离知识库中，将 100 份 PDF 通过 DeepDoc、真实检索卡生成、百炼 Embedding 和双索引完整入库，据实际 READY 和证据页结果冻结正式评测集；不执行 Retrieve/Answer 质量评分。
- N5.2：使用该租户的专用 Application Credential 和 READ Grant，只对 P2 冻结子集通过 `/retrieve`、`/answer` 执行真实质量、费用和端到端延迟评测；必须另行冻结指标、失败处理和报告门禁，并取得明确开始授权。
- N5 后续：只补少量可靠性和性能证据；不得自动扩展为大规模自制数据集、多轮 Agent 或无边界故障矩阵。

`mmlongbench-docquery-v1` 已完成确定性适配和真实租户入库：最终 100 份上游真实 PDF、64 个纯文本证据可回答问题、16 个不可回答问题、69 份 case 来源文档和 31 份干扰文档。N5.1-P0、P1、P2 均已实现、验证并经用户确认完成；P2 最终为 100/100 READY、80/80 case 保留，Canonical/Retrieval/Projection 各 100，Evidence 78,062/78,062，Navigation 2,422/2,422。真实 Retrieve/Answer 质量、查询成本和端到端延迟仍为 `NOT_EXECUTED`，N5.2 尚未开始。

### 15.1 故障验证

- 重复 RabbitMQ 消息
- Consumer 在解析、检索卡生成、Embedding 和索引阶段分别崩溃
- RabbitMQ 中断、PENDING Outbox 积压与恢复
- 消息已经发布但 Outbox 尚未标记 SENT 时进程崩溃
- 对象存储短暂不可用
- Chat Model 生成检索卡超时或返回非法结构
- Embedding 服务超时
- Elasticsearch 写入部分失败
- Redis 在幂等请求执行前、执行中和结果写入时不可用
- 版本切换时并发查询
- 删除时并发查询

### 15.2 权限验证

- 未授权知识库访问
- 跨租户访问
- 已撤销凭证
- 已停用应用
- 已停用知识库
- 伪造租户字段

### 15.3 检索验证

使用固定问题集记录：

- 关键词单路、向量导航单路和 RRF 融合的 Top-K 命中率
- MRR 或 nDCG
- 文档、真实章节和原文位置命中率
- 引用正确率
- 无答案识别
- 长章节内部定位效果
- Agent 平均工具轮数、上下文用量和模型调用成本

### 15.4 性能验证

记录：

- 吞吐量
- 错误率
- P50、P95、P99
- 任务队列积压
- 积压恢复速度
- 单份文档各处理阶段耗时

报告必须注明硬件、数据规模、依赖配置和脚本，不能只给一个 QPS 数字。

---

## 16. N6：外部契约和演示交付

### 16.1 外部契约

在 MVP 行为稳定后，从真实实现整理：

- 管理 API
- 服务 API
- 应用鉴权
- 错误码
- 幂等规则
- 状态枚举
- 限制项
- 调用示例

契约需要由自动化测试保护，避免文档与实现漂移。

### 16.2 售后工单 Demo

Demo 模拟一个独立的传统业务系统：

1. 业务用户登录工单系统。
2. 工单系统自行判断用户是否能查看当前工单。
3. 工单后端使用自己的应用凭证。
4. 请求明确指定售后维修知识库。
5. DocQuery 校验应用授权。
6. DocQuery 返回原文证据和引用，或执行受控单轮问答。
7. 工单页面展示原文证据或带引用答案。

Demo 的重点是证明产品边界和接入价值，不只是展示聊天界面。

### 16.3 最终交付物

- 产品设计
- 管理后台页面说明和界面稿
- 新服务开发计划
- 独立部署说明
- 部署和接入指南
- 售后工单 Demo
- 契约与调用示例
- 自动化测试报告
- 检索效果报告
- 压测和故障恢复报告

---

## 17. 开发批次

### 批次 1：创建干净的新仓库

- 独立构建
- 传统 Spring Boot 分层目录
- 单个启动应用
- 测试框架
- 面向全新空库的数据库版本管理
- 本地依赖编排
- 配置和日志安全基线

### 批次 2：建立产品身份骨架

- Tenant
- AdminUser
- Application
- Credential
- KnowledgeBase
- ApplicationGrant
- 授权矩阵测试

### 批次 3：打通一份文档

- Document
- DocumentVersion
- ProcessingJob
- OutboxEvent
- 对象存储对象
- RabbitMQ 发布、消费、重试和死信
- PDF、DOCX、TXT、Markdown 解析和标准化原文
- 真实标题树、DocumentProfile 和 RetrievalNode
- LangChain4j Chat/Embedding Gateway
- ES 关键词和导航向量索引适配器

### 批次 4：打通应用检索

- 应用凭证校验
- Grant 校验
- Redis Idempotency-Key 和短期结果复用
- 知识库级 BM25 与 KNN 前置过滤
- Java RRF 融合
- 原文证据和引用
- 受控单轮 Agent 工具循环
- 访问审计

### 批次 5：补齐产品生命周期

- 新版本
- 失败重试
- 删除
- 凭证轮换
- 管理后台
- 售后工单 Demo

### 批次 6：形成工程证据

- 集成测试
- 故障注入
- 压测
- 检索效果评估
- 外部契约
- 部署和接入材料

---

## 18. 完成定义

一个批次只有同时满足以下条件才算完成：

- 对应产品行为可以演示
- 正常、异常和越权测试通过
- 数据库可以从空库迁移
- 重试不产生重复业务数据
- 日志不包含凭证和敏感正文
- 关键操作可以通过请求 ID 追踪
- 文档与真实实现一致
- 不依赖旧工程、旧数据库或旧接口

文档版本处理完成并可查询时，统一称为“可检索（`READY`）”。任务执行成功不自动等于文档已经可检索。

---

## 19. 下一步

N0—N4.4 均已完成并经用户验收，N3、N4.1—N4.4 已关闭。N5.1-P0、P1、P2 也均已实现、验证并经用户确认完成；“测试科技”评测知识库最终 100/100 READY，80/80 case 已冻结。下一步只能讨论并冻结 N5.2 的最小指标、预算、失败处理、报告和验收点；在获得独立的明确开始授权前，不执行 `/retrieve`、`/answer` 正式评测。

每个 N2 子阶段开始前必须明确：

- 产品结果和非目标
- 数据对象和状态变化
- 依赖和配置
- 正常、失败、重复和恢复验收
- 本阶段文档位置

只有用户明确确认开始对应阶段后才能修改代码或执行评测；完成并提供证据后再次等待验收，不能自动进入下一阶段。N4.1—N4.4、N5.1-P0、N5.1-P1 与 N5.1-P2 均已由用户明确确认完成。P2 的完成不授权开始 N5.2、多轮 Agent 或其他阶段；成熟公开数据集上的真实 Retrieve/Answer 质量、查询成本和端到端延迟评测仍为 `NOT_EXECUTED`，不构成真实质量或性能声明。
