import { CopyOutlined, ExperimentOutlined, ReloadOutlined, SendOutlined } from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError, api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { ErrorState, PageLoading } from '../../components/PageState'
import type { AnswerResponse, RetrievalMode, RetrieveResponse, ServiceCallResult } from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'

type Operation = 'retrieve' | 'answer'
interface PlaygroundForm {
  operation: Operation
  applicationId: number
  knowledgeBaseId: number
  credential: string
  query: string
  mode: RetrievalMode
  topK: number
  traceId?: string
  actorRef?: string
}

interface ExecutionResult {
  operation: Operation
  result: ServiceCallResult<RetrieveResponse | AnswerResponse>
  idempotencyKey: string
}

const newKey = () => crypto.randomUUID()

export function ApiPlaygroundPage() {
  const tenantId = useTenantId()
  const [form] = Form.useForm<PlaygroundForm>()
  const [idempotencyKey, setIdempotencyKey] = useState(newKey)
  const [execution, setExecution] = useState<ExecutionResult>()
  const [failure, setFailure] = useState<ApiError>()
  const applicationId = Form.useWatch('applicationId', form)
  const applications = useQuery({
    queryKey: ['applications', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listApplications(tenantId, page, size)),
  })
  const knowledgeBases = useQuery({
    queryKey: ['knowledge-bases', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listKnowledgeBases(tenantId, page, size)),
  })
  const grants = useQuery({
    queryKey: ['grants', tenantId, 'application', applicationId],
    queryFn: () => collectAllPages((page, size) => api.listGrantsByApplication(tenantId, applicationId, page, size)),
    enabled: Number.isSafeInteger(applicationId) && applicationId > 0,
  })

  const execute = useMutation({
    mutationFn: async (values: PlaygroundForm) => {
      const request = {
        query: values.query.trim(),
        mode: values.mode,
        topK: values.topK,
      }
      const context = {
        traceId: values.traceId?.trim() || undefined,
        actorRef: values.actorRef?.trim() || undefined,
      }
      const result = values.operation === 'retrieve'
        ? await api.retrieve(values.knowledgeBaseId, values.credential.trim(), idempotencyKey, request, context)
        : await api.answer(values.knowledgeBaseId, values.credential.trim(), idempotencyKey, request, context)
      return { operation: values.operation, result, idempotencyKey } satisfies ExecutionResult
    },
    onMutate: () => { setFailure(undefined); setExecution(undefined) },
    onSuccess: (result) => {
      setExecution(result)
      setIdempotencyKey(newKey())
    },
    onError: (error) => setFailure(error instanceof ApiError
      ? error
      : new ApiError(0, 'NETWORK_ERROR', '无法连接 DocQuery 服务')),
  })

  if (knowledgeBases.isLoading || applications.isLoading) return <PageLoading />
  if (knowledgeBases.isError || applications.isError) {
    const failed = knowledgeBases.isError ? knowledgeBases : applications
    return <ErrorState error={failed.error} onRetry={() => failed.refetch()} />
  }

  const readableKnowledgeBaseIds = new Set((grants.data ?? [])
    .filter((grant) => grant.status === '1' && (grant.permission === '1' || grant.permission === '3'))
    .map((grant) => grant.knowledgeBaseId))

  const copy = (value: string, label: string) => navigator.clipboard.writeText(value)
    .then(() => message.success(`${label}已复制`))

  return (
    <>
      <PageHeader
        title="API 调试"
        description="使用真实 Application Credential 调用 /retrieve 或 /answer，验证外部系统接入链路。"
        action={<Link to="/guide"><Button>查看使用说明</Button></Link>}
      />
      <Alert
        showIcon
        type="warning"
        title="Credential 仅保存在当前页面内存"
        description="页面不会写入 LocalStorage、SessionStorage、URL 或查询缓存；离开或刷新页面后需重新输入。请勿使用生产 Credential 做演示。"
        className="page-alert"
      />
      <Row gutter={20} align="top">
        <Col span={10}>
          <Card title={<Space><ExperimentOutlined />请求配置</Space>}>
            <Form
              form={form}
              layout="vertical"
              initialValues={{ operation: 'retrieve', mode: 'HYBRID', topK: 5 }}
              onValuesChange={(changed) => {
                if ('applicationId' in changed) form.setFieldValue('knowledgeBaseId', undefined)
              }}
              onFinish={(values) => execute.mutate(values)}
            >
              <Form.Item name="operation" label="接口">
                <Segmented block options={[{ value: 'retrieve', label: '/retrieve' }, { value: 'answer', label: '/answer' }]} />
              </Form.Item>
              <Form.Item name="applicationId" label="Application" rules={[{ required: true, message: '请选择应用' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择 Credential 所属应用"
                  options={(applications.data ?? []).map((application) => ({
                    value: application.id,
                    label: `${application.name} · ${application.code}${application.status === '1' ? '' : ' · 已停用'}`,
                    disabled: application.status !== '1',
                  }))}
                />
              </Form.Item>
              <Form.Item name="knowledgeBaseId" label="KnowledgeBase" rules={[{ required: true, message: '请选择知识库' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  loading={grants.isLoading}
                  disabled={!applicationId}
                  placeholder={applicationId ? '选择已获读取授权的知识库' : '请先选择应用'}
                  options={(knowledgeBases.data ?? []).filter((knowledgeBase) => readableKnowledgeBaseIds.has(knowledgeBase.id)).map((knowledgeBase) => ({
                    value: knowledgeBase.id,
                    label: `${knowledgeBase.name} · ID ${knowledgeBase.id}${knowledgeBase.status === '1' ? '' : ' · 已停用'}`,
                    disabled: knowledgeBase.status !== '1',
                  }))}
                />
              </Form.Item>
              <Form.Item
                name="credential"
                label="Application Credential"
                rules={[{ required: true, whitespace: true, message: '请输入 Credential' }]}
                extra={<>Credential 只能在创建时复制。没有可用 Credential？前往<Link to="/applications">应用管理</Link>创建。</>}
              >
                <Input.Password autoComplete="off" placeholder="dq_app_..." visibilityToggle />
              </Form.Item>
              <Form.Item name="query" label="问题" rules={[{ required: true, whitespace: true }, { max: 2000 }]}>
                <Input.TextArea rows={4} maxLength={2000} showCount placeholder="例如：差旅住宿报销标准是什么？" />
              </Form.Item>
              <Row gutter={12}>
                <Col span={14}>
                  <Form.Item name="mode" label="召回模式" rules={[{ required: true }]}>
                    <Select options={[
                      { value: 'HYBRID', label: 'HYBRID · 混合召回' },
                      { value: 'KEYWORD', label: 'KEYWORD · BM25' },
                      { value: 'SEMANTIC', label: 'SEMANTIC · 向量召回' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={10}>
                  <Form.Item name="topK" label="Top-K" rules={[{ required: true }]}>
                    <InputNumber min={1} max={20} className="full-width" />
                  </Form.Item>
                </Col>
              </Row>
              <Collapse
                ghost
                items={[{
                  key: 'context',
                  label: '可选调用上下文',
                  children: (
                    <>
                      <Form.Item name="traceId" label="X-DocQuery-Trace-Id"><Input maxLength={128} placeholder="业务链路追踪 ID" /></Form.Item>
                      <Form.Item name="actorRef" label="X-DocQuery-Actor-Ref"><Input maxLength={128} placeholder="业务系统中的用户/主体引用" /></Form.Item>
                    </>
                  ),
                }]}
              />
              <Divider />
              <Space orientation="vertical" className="full-width">
                <Typography.Text type="secondary">Idempotency-Key</Typography.Text>
                <Input
                  readOnly
                  value={idempotencyKey}
                  suffix={<Button type="text" size="small" icon={<ReloadOutlined />} onClick={() => setIdempotencyKey(newKey())}>换一个</Button>}
                />
                <Button block type="primary" htmlType="submit" icon={<SendOutlined />} loading={execute.isPending}>发送真实请求</Button>
              </Space>
            </Form>
          </Card>
        </Col>
        <Col span={14}>
          <Card title="执行结果" className="query-result-card">
            {!execute.isPending && !execution && !failure && (
              <div className="query-result-placeholder"><Typography.Text type="secondary">填写左侧参数并发送请求后，这里展示真实结果、引用和请求标识。</Typography.Text></div>
            )}
            {execute.isPending && <PageLoading rows={8} />}
            {failure && (
              <Alert
                type="error"
                showIcon
                title={`请求失败 · HTTP ${failure.status || 'NETWORK'}`}
                description={<Space orientation="vertical"><Typography.Text>{failure.message}</Typography.Text><Typography.Text code>{failure.code}</Typography.Text></Space>}
              />
            )}
            {execution && (
              <>
                <Descriptions size="small" column={2} bordered>
                  <Descriptions.Item label="Request ID">
                    <Space>{execution.result.requestId ?? '—'}{execution.result.requestId && <Button type="text" size="small" icon={<CopyOutlined />} onClick={() => copy(execution.result.requestId!, 'Request ID')} />}</Space>
                  </Descriptions.Item>
                  <Descriptions.Item label="Query Execution ID">{execution.result.data.queryExecutionId}</Descriptions.Item>
                  <Descriptions.Item label="实际模式"><Tag color="blue">{execution.result.data.executedMode}</Tag></Descriptions.Item>
                  <Descriptions.Item label="降级">{execution.result.data.degraded ? <Tag color="warning">{execution.result.data.degradationReason ?? '是'}</Tag> : <Tag color="success">否</Tag>}</Descriptions.Item>
                </Descriptions>
                <Divider />
                {execution.operation === 'retrieve'
                  ? <RetrieveView data={execution.result.data as RetrieveResponse} />
                  : <AnswerView data={execution.result.data as AnswerResponse} />}
                <Collapse
                  className="raw-response"
                  items={[{
                    key: 'raw',
                    label: '查看原始 JSON',
                    children: <pre>{JSON.stringify(execution.result.data, null, 2)}</pre>,
                  }]}
                />
              </>
            )}
          </Card>
        </Col>
      </Row>
    </>
  )
}

function RetrieveView({ data }: { data: RetrieveResponse }) {
  if (data.results.length === 0) return <Alert type="info" showIcon title="未召回结果" />
  return (
    <Space orientation="vertical" size={12} className="full-width">
      {data.results.map((result) => (
        <Card key={`${result.documentVersionId}-${result.rank}`} size="small" title={`#${result.rank} · ${result.documentName}`} extra={result.channels.map((channel) => <Tag key={channel}>{channel}</Tag>)}>
          <Typography.Paragraph type="secondary">{result.headingPath.length > 0 ? result.headingPath.join(' / ') : '文档正文'}</Typography.Paragraph>
          {result.evidence.map((evidence) => (
            <blockquote key={evidence.blockId} className="evidence-block">
              {evidence.keywordHighlights.length > 0
                ? evidence.keywordHighlights.flatMap((fragment) => fragment.segments).map((segment, index) => segment.matched
                    ? <mark key={index}>{segment.text}</mark>
                    : <span key={index}>{segment.text}</span>)
                : evidence.text}
              {evidence.truncated && <Typography.Text type="secondary">…</Typography.Text>}
            </blockquote>
          ))}
        </Card>
      ))}
    </Space>
  )
}

function AnswerView({ data }: { data: AnswerResponse }) {
  return (
    <Space orientation="vertical" size={16} className="full-width">
      <Alert type={data.answer ? 'success' : 'warning'} showIcon title={`回答状态：${data.status}`} />
      <Typography.Paragraph className="answer-text">{data.answer ?? '当前证据不足，未生成回答。'}</Typography.Paragraph>
      <Typography.Title level={5}>引用证据（{data.citations.length}）</Typography.Title>
      {data.citations.map((citation) => (
        <Card key={citation.evidenceId} size="small" title={`[${citation.evidenceId}] ${citation.documentName}`}>
          <Typography.Paragraph type="secondary">{citation.headingPath.length > 0 ? citation.headingPath.join(' / ') : '文档正文'}</Typography.Paragraph>
          <blockquote className="evidence-block">{citation.text}{citation.truncated ? '…' : ''}</blockquote>
        </Card>
      ))}
    </Space>
  )
}
