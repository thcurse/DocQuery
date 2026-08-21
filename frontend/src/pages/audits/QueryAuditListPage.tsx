import { AuditOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Form, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { ErrorState, PageLoading } from '../../components/PageState'
import { formatDateTime } from '../../domain/display'
import type { QueryAudit } from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'

interface AuditFilters {
  applicationId?: number
  knowledgeBaseId?: number
  operation?: string
  outcome?: string
  requestId?: string
  queryExecutionId?: string
  traceId?: string
}

const outcomeColor = (outcome: string) => ({
  SUCCEEDED: 'success',
  FAILED: 'error',
  REJECTED: 'warning',
  STARTED: 'processing',
  INTERRUPTED: 'default',
}[outcome] ?? 'default')

export function QueryAuditListPage() {
  const tenantId = useTenantId()
  const [form] = Form.useForm<AuditFilters>()
  const [filters, setFilters] = useState<AuditFilters>({})
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const applications = useQuery({
    queryKey: ['applications', tenantId, 'all'],
    queryFn: () => collectAllPages((nextPage, nextSize) => api.listApplications(tenantId, nextPage, nextSize)),
  })
  const knowledgeBases = useQuery({
    queryKey: ['knowledge-bases', tenantId, 'all'],
    queryFn: () => collectAllPages((nextPage, nextSize) => api.listKnowledgeBases(tenantId, nextPage, nextSize)),
  })
  const audits = useQuery({
    queryKey: ['query-audits', tenantId, page, size, filters],
    queryFn: () => api.listQueryAudits(tenantId, { ...filters, page, size }),
  })
  const applicationById = useMemo(() => new Map(
    (applications.data ?? []).map((application) => [application.id, application]),
  ), [applications.data])
  const knowledgeBaseById = useMemo(() => new Map(
    (knowledgeBases.data ?? []).map((knowledgeBase) => [knowledgeBase.id, knowledgeBase]),
  ), [knowledgeBases.data])

  const columns = [
    {
      title: '请求',
      render: (_: unknown, audit: QueryAudit) => (
        <Space orientation="vertical" size={0}>
          <Link to={`/query-audits/${audit.id}`}><Typography.Text strong>{audit.operation}</Typography.Text></Link>
          <Typography.Text type="secondary" className="mono-small">{audit.requestId}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '调用主体',
      render: (_: unknown, audit: QueryAudit) => (
        <Space orientation="vertical" size={0}>
          <span>{applicationById.get(audit.applicationId)?.name ?? `应用 #${audit.applicationId}`}</span>
          <Typography.Text type="secondary">{audit.actorRef || audit.credentialFingerprint}</Typography.Text>
        </Space>
      ),
    },
    { title: '知识库', render: (_: unknown, audit: QueryAudit) => knowledgeBaseById.get(audit.knowledgeBaseId)?.name ?? `#${audit.knowledgeBaseId}` },
    { title: '结果', dataIndex: 'outcome', render: (outcome: string) => <Tag color={outcomeColor(outcome)}>{outcome}</Tag> },
    { title: 'HTTP', dataIndex: 'httpStatus' },
    { title: '耗时', dataIndex: 'durationMs', render: (value: number | null) => value === null ? '—' : `${value} ms` },
    { title: '开始时间', dataIndex: 'startedAt', render: formatDateTime },
    { title: '操作', render: (_: unknown, audit: QueryAudit) => <Link to={`/query-audits/${audit.id}`}>查看详情</Link> },
  ]

  return (
    <>
      <PageHeader title="查询审计" description="追踪应用对知识库的调用结果、性能和失败原因；不保存问题、回答或原文正文。" />
      <Card className="detail-summary-card">
        <Form
          form={form}
          layout="inline"
          className="audit-filter-form"
          onFinish={(values) => { setFilters(values); setPage(0) }}
        >
          <Form.Item name="applicationId">
            <Select allowClear showSearch optionFilterProp="label" placeholder="应用" style={{ width: 180 }} options={(applications.data ?? []).map((application) => ({ value: application.id, label: `${application.name} · ${application.code}` }))} />
          </Form.Item>
          <Form.Item name="knowledgeBaseId">
            <Select allowClear showSearch optionFilterProp="label" placeholder="知识库" style={{ width: 180 }} options={(knowledgeBases.data ?? []).map((knowledgeBase) => ({ value: knowledgeBase.id, label: knowledgeBase.name }))} />
          </Form.Item>
          <Form.Item name="operation"><Select allowClear placeholder="接口" style={{ width: 130 }} options={[{ value: 'RETRIEVE', label: 'RETRIEVE' }, { value: 'ANSWER', label: 'ANSWER' }]} /></Form.Item>
          <Form.Item name="outcome"><Select allowClear placeholder="结果" style={{ width: 140 }} options={['SUCCEEDED', 'FAILED', 'REJECTED', 'STARTED', 'INTERRUPTED'].map((value) => ({ value, label: value }))} /></Form.Item>
          <Form.Item name="requestId"><Input allowClear placeholder="Request ID" style={{ width: 210 }} /></Form.Item>
          <Form.Item><Button type="primary" htmlType="submit" icon={<SearchOutlined />}>筛选</Button></Form.Item>
          <Form.Item><Button icon={<ReloadOutlined />} onClick={() => { form.resetFields(); setFilters({}); setPage(0) }}>重置</Button></Form.Item>
          <details className="audit-advanced-filter">
            <summary>更多标识筛选</summary>
            <Space wrap>
              <Form.Item name="queryExecutionId"><Input allowClear placeholder="Query Execution ID" style={{ width: 260 }} /></Form.Item>
              <Form.Item name="traceId"><Input allowClear placeholder="Trace ID" style={{ width: 260 }} /></Form.Item>
            </Space>
          </details>
        </Form>
      </Card>
      <Card title={<Space><AuditOutlined />审计记录</Space>}>
        {audits.isLoading ? <PageLoading /> : audits.isError
          ? <ErrorState error={audits.error} onRetry={() => audits.refetch()} />
          : <Table
              rowKey="id"
              columns={columns}
              dataSource={audits.data?.items}
              pagination={{
                current: page + 1,
                pageSize: size,
                total: audits.data?.total,
                showSizeChanger: true,
                onChange: (nextPage, nextSize) => { setPage(nextPage - 1); setSize(nextSize) },
              }}
            />}
      </Card>
    </>
  )
}
