import { CopyOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Space, Tag, Typography, message } from 'antd'
import { useParams } from 'react-router'
import { api } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { ErrorState, PageLoading } from '../../components/PageState'
import { formatDateTime } from '../../domain/display'
import { useTenantId } from '../../hooks/useTenantId'

const show = (value: unknown) => value === null || value === undefined || value === '' ? '—' : String(value)

export function QueryAuditDetailPage() {
  const tenantId = useTenantId()
  const auditId = Number(useParams().auditId)
  const audit = useQuery({
    queryKey: ['query-audit', tenantId, auditId],
    queryFn: () => api.getQueryAudit(tenantId, auditId),
    enabled: Number.isSafeInteger(auditId) && auditId > 0,
  })
  if (!Number.isSafeInteger(auditId) || auditId <= 0) return <ErrorState error={new Error('Invalid audit id')} />
  if (audit.isLoading) return <PageLoading />
  if (audit.isError) return <ErrorState error={audit.error} onRetry={() => audit.refetch()} />
  if (!audit.data) return null
  const data = audit.data
  const copy = (value: string, label: string) => navigator.clipboard.writeText(value).then(() => message.success(`${label}已复制`))
  return (
    <>
      <PageHeader
        title={`${data.operation} 查询审计`}
        description={`Audit ID：${data.id}`}
        crumbs={[{ title: '查询审计', to: '/query-audits' }, { title: `#${data.id}` }]}
      />
      <Alert showIcon type="info" title="隐私边界" description="审计只记录摘要、调用主体、结果和计数，不记录 Credential、问题原文、回答正文或引用原文。" className="page-alert" />
      <Card title="请求与主体" className="detail-summary-card">
        <Descriptions column={2} bordered>
          <Descriptions.Item label="Request ID"><Space>{data.requestId}<Button type="text" size="small" icon={<CopyOutlined />} onClick={() => copy(data.requestId, 'Request ID')} /></Space></Descriptions.Item>
          <Descriptions.Item label="Query Execution ID">{data.queryExecutionId ? <Space>{data.queryExecutionId}<Button type="text" size="small" icon={<CopyOutlined />} onClick={() => copy(data.queryExecutionId!, 'Query Execution ID')} /></Space> : '—'}</Descriptions.Item>
          <Descriptions.Item label="Application ID">{data.applicationId}</Descriptions.Item>
          <Descriptions.Item label="KnowledgeBase ID">{data.knowledgeBaseId}</Descriptions.Item>
          <Descriptions.Item label="Credential 指纹"><Typography.Text code>{data.credentialFingerprint}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="Actor Ref">{show(data.actorRef)}</Descriptions.Item>
          <Descriptions.Item label="Caller Trace ID">{show(data.callerTraceId)}</Descriptions.Item>
          <Descriptions.Item label="Query SHA-256"><Typography.Text className="mono-small">{data.querySha256}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="问题长度">{data.queryCodePoints} 个字符</Descriptions.Item>
          <Descriptions.Item label="幂等处理">{show(data.idempotencyDisposition)}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="执行结果" className="detail-summary-card">
        <Descriptions column={3} bordered>
          <Descriptions.Item label="结果"><Tag color={data.outcome === 'SUCCEEDED' ? 'success' : data.outcome === 'FAILED' ? 'error' : 'warning'}>{data.outcome}</Tag></Descriptions.Item>
          <Descriptions.Item label="HTTP 状态">{data.httpStatus}</Descriptions.Item>
          <Descriptions.Item label="耗时">{data.durationMs === null ? '—' : `${data.durationMs} ms`}</Descriptions.Item>
          <Descriptions.Item label="请求模式">{data.requestedMode}</Descriptions.Item>
          <Descriptions.Item label="执行模式">{show(data.executedMode)}</Descriptions.Item>
          <Descriptions.Item label="降级">{data.degraded ? <Tag color="warning">是</Tag> : <Tag color="success">否</Tag>}</Descriptions.Item>
          <Descriptions.Item label="失败类别">{show(data.failureCategory)}</Descriptions.Item>
          <Descriptions.Item label="失败码">{show(data.failureCode)}</Descriptions.Item>
          <Descriptions.Item label="降级原因">{show(data.degradationReason)}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{formatDateTime(data.startedAt)}</Descriptions.Item>
          <Descriptions.Item label="完成时间">{formatDateTime(data.completedAt)}</Descriptions.Item>
          <Descriptions.Item label="Snapshot 指纹"><Typography.Text className="mono-small">{show(data.snapshotFingerprint)}</Typography.Text></Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="结果计数">
        <Descriptions column={4} bordered>
          <Descriptions.Item label="Active Versions">{show(data.activeVersionCount)}</Descriptions.Item>
          <Descriptions.Item label="Results">{show(data.resultCount)}</Descriptions.Item>
          <Descriptions.Item label="Evidence">{show(data.evidenceCount)}</Descriptions.Item>
          <Descriptions.Item label="Citations">{show(data.citationCount)}</Descriptions.Item>
          <Descriptions.Item label="Answer Status">{show(data.answerStatus)}</Descriptions.Item>
          <Descriptions.Item label="Tool Rounds / Calls">{show(data.toolRounds)} / {show(data.toolCalls)}</Descriptions.Item>
          <Descriptions.Item label="Model Calls">{show(data.modelCalls)}</Descriptions.Item>
          <Descriptions.Item label="Canonical 字符">{show(data.canonicalCharacters)}</Descriptions.Item>
        </Descriptions>
      </Card>
    </>
  )
}
