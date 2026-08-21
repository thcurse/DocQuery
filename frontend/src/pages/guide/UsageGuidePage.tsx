import { CopyOutlined, DownloadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Collapse, Divider, Space, Steps, Table, Tag, Typography, message } from 'antd'
import { Link } from 'react-router'
import { useAuth } from '../../auth/AuthContext'
import { PageHeader } from '../../components/PageHeader'

const retrieveCurl = `curl --request POST "{{baseUrl}}/api/v1/service/knowledge-bases/{{knowledgeBaseId}}/retrieve" \\
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

const answerCurl = `curl --request POST "{{baseUrl}}/api/v1/service/knowledge-bases/{{knowledgeBaseId}}/answer" \\
  --header "Authorization: Bearer {{credential}}" \\
  --header "Idempotency-Key: $(uuidgen)" \\
  --header "Content-Type: application/json" \\
  --data '{
    "query": "差旅住宿报销标准是什么？",
    "mode": "HYBRID",
    "topK": 5
  }'`

const javaExample = `HttpRequest request = HttpRequest.newBuilder()
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

const errors = [
  { status: 400, code: 'INVALID_RETRIEVE_REQUEST / INVALID_ANSWER_REQUEST', handling: '修正 query、mode、topK 或必需请求头后，不复用错误请求参数。' },
  { status: 401, code: 'APPLICATION_CREDENTIAL_INVALID', handling: '检查 Credential 是否完整、是否已撤销；不要自动无限重试。' },
  { status: 404, code: 'KNOWLEDGE_BASE_NOT_AVAILABLE', handling: '检查 KB 状态、应用状态及 READ/READ_WRITE Grant。' },
  { status: 409, code: 'REQUEST_IN_PROGRESS', handling: '同一 Idempotency-Key 正在执行，短暂退避后用原 Key 查询同一请求。' },
  { status: 409, code: 'IDEMPOTENCY_CONFLICT', handling: '该 Key 已绑定其他请求，生成新 Key 后再提交新请求。' },
  { status: 409, code: 'IDEMPOTENCY_CONTEXT_CHANGED', handling: 'activeVersion 快照已变化，生成新 Key 明确发起新请求。' },
  { status: 503, code: 'QUERY_IDEMPOTENCY_UNAVAILABLE', handling: '幂等设施不可用，指数退避；不要绕过 Idempotency-Key。' },
  { status: 503, code: 'QUERY_EMBEDDING_UNAVAILABLE / SEARCH_UNAVAILABLE / EVIDENCE_UNAVAILABLE', handling: '依赖暂不可用，按业务超时预算有限重试。' },
  { status: 503, code: 'ANSWER_MODEL_UNAVAILABLE / ANSWER_OUTPUT_INVALID / ANSWER_EXECUTION_*', handling: 'Answer 链路失败，可降级调用 /retrieve 或提示稍后重试。' },
]

export function UsageGuidePage() {
  const { admin } = useAuth()
  const platformAdministrator = admin?.role === '1'
  const copy = (value: string, label: string) => navigator.clipboard.writeText(value).then(() => message.success(`${label}已复制`))
  return (
    <>
      <PageHeader
        title="使用说明"
        description="把 DocQuery 接入传统业务后端所需的资源准备、接口契约、示例与失败处理集中在这里。"
        action={(
          <Space>
            <Button href="/admin/docs/docquery-service-api.openapi.yaml" download icon={<DownloadOutlined />}>下载 OpenAPI</Button>
            <Button type="primary" href="/admin/docs/docquery-service-api.postman_collection.json" download icon={<DownloadOutlined />}>下载 Postman Collection</Button>
          </Space>
        )}
      />
      <Alert
        showIcon
        type="info"
        title="接入边界"
        description="业务系统负责最终用户登录和业务权限；DocQuery 负责租户隔离、Application Credential、KnowledgeBase Grant、检索、回答和查询审计。Credential 只能放在业务后端，禁止下发到浏览器或移动端。"
        className="page-alert"
      />
      {platformAdministrator && (
        <Card title="平台管理员先完成租户开通" className="detail-summary-card">
          <Steps
            responsive={false}
            items={[
              { title: '创建 Tenant', description: <Link to="/tenants">进入租户管理</Link> },
              { title: '创建首个管理员', description: '创建 Tenant 时同时设置租户管理员账号和密码' },
              { title: '安全交付账号', description: '由租户管理员登录后配置应用、知识库与授权' },
            ]}
          />
          <Divider />
          <Typography.Paragraph>
            Tenant 详情支持继续新增、启停租户管理员及重置密码。平台管理员可以阅读本页契约，但不能绕过租户管理员身份直接操作 Application、KnowledgeBase、Document 或查询接口。
          </Typography.Paragraph>
        </Card>
      )}
      <Card title="五步完成接入" className="detail-summary-card">
        <Steps
          responsive={false}
          items={[
            { title: '创建应用', description: platformAdministrator ? '由租户管理员配置环境与状态' : <Link to="/applications">配置环境与状态</Link> },
            { title: '创建 Credential', description: '创建时立即安全保存完整值' },
            { title: '创建知识库', description: platformAdministrator ? '由租户管理员复制 KnowledgeBase ID' : <Link to="/knowledge-bases">复制 KnowledgeBase ID</Link> },
            { title: '授予 READ', description: '在应用或知识库详情建立 Grant' },
            { title: '后端调用', description: '携带 Credential 与幂等键' },
          ]}
        />
        <Divider />
        <Typography.Paragraph>
          外部服务不需要“猜” KnowledgeBase ID：租户管理员在{platformAdministrator ? '知识库管理' : <Link to="/knowledge-bases">知识库管理</Link>}进入详情，点击“复制 ID”，再把 ID 作为受控配置交给对应业务应用。Application ID 和 Document ID 的管理页面也提供复制入口。
        </Typography.Paragraph>
      </Card>

      <Card title="服务契约" className="detail-summary-card">
        <Table
          pagination={false}
          rowKey="path"
          dataSource={[
            { path: '/api/v1/service/knowledge-bases/{knowledgeBaseId}/retrieve', purpose: '返回排序后的原文证据，适合业务系统自行展示或消费。', agent: '否' },
            { path: '/api/v1/service/knowledge-bases/{knowledgeBaseId}/answer', purpose: '基于证据生成受控单轮回答，并返回可核验引用。', agent: '受控单轮' },
          ]}
          columns={[
            { title: 'POST 路径', dataIndex: 'path', render: (value: string) => <Typography.Text code copyable>{value}</Typography.Text> },
            { title: '用途', dataIndex: 'purpose' },
            { title: 'Agent', dataIndex: 'agent', render: (value: string) => <Tag>{value}</Tag> },
          ]}
        />
        <Divider />
        <Space wrap>
          <Tag color="red">Authorization: Bearer dq_app_...</Tag>
          <Tag color="blue">Idempotency-Key: UUID</Tag>
          <Tag>X-DocQuery-Trace-Id: 可选</Tag>
          <Tag>X-DocQuery-Actor-Ref: 可选</Tag>
          <Tag>Content-Type: application/json</Tag>
        </Space>
        <Typography.Paragraph type="secondary" className="guide-note">
          成功响应头包含 <Typography.Text code>X-DocQuery-Request-Id</Typography.Text>。排障时优先记录它，并在“查询审计”中筛选。
        </Typography.Paragraph>
      </Card>

      <Card title="可复制示例" className="detail-summary-card">
        <Collapse
          defaultActiveKey={['retrieve']}
          items={[
            { key: 'retrieve', label: 'curl · /retrieve', children: <CodeBlock code={retrieveCurl} onCopy={() => copy(retrieveCurl, 'curl 示例')} /> },
            { key: 'answer', label: 'curl · /answer', children: <CodeBlock code={answerCurl} onCopy={() => copy(answerCurl, 'curl 示例')} /> },
            { key: 'java', label: 'Java 17 HttpClient · /retrieve', children: <CodeBlock code={javaExample} onCopy={() => copy(javaExample, 'Java 示例')} /> },
          ]}
        />
        <Alert showIcon type="warning" title="示例中的变量必须替换" description="{{baseUrl}}、{{knowledgeBaseId}}、{{credential}} 是占位符；每个新业务请求生成新的 Idempotency-Key，同一请求超时重试时复用原 Key。" className="section-alert guide-example-alert" />
      </Card>

      <Card title="失败处理与重试边界">
        <Table
          rowKey={(row) => `${row.status}-${row.code}`}
          pagination={false}
          dataSource={errors}
          columns={[
            { title: 'HTTP', dataIndex: 'status', width: 80 },
            { title: '错误码', dataIndex: 'code', render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
            { title: '业务系统处理', dataIndex: 'handling' },
          ]}
        />
      </Card>
    </>
  )
}

function CodeBlock({ code, onCopy }: { code: string; onCopy: () => void }) {
  return (
    <div className="code-block-wrap">
      <Button className="code-copy" size="small" icon={<CopyOutlined />} onClick={onCopy}>复制</Button>
      <pre>{code}</pre>
    </div>
  )
}
