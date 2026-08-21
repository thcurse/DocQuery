import { CopyOutlined, EditOutlined, FileTextOutlined, LinkOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Descriptions, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { EmptyState, ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { StatusTag } from '../../components/StatusTag'
import { environmentLabel, formatDateTime, permissionLabel } from '../../domain/display'
import type { ApplicationGrant, GrantPermission } from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'
import { DocumentListPanel } from '../documents/DocumentListPanel'
import { queryClient } from '../../queryClient'

interface EditForm { name: string; description?: string }
interface GrantForm { applicationId: number; permission: GrantPermission }

export function KnowledgeBaseDetailPage() {
  const tenantId = useTenantId()
  const knowledgeBaseId = Number(useParams().knowledgeBaseId)
  const [editing, setEditing] = useState(false)
  const [grantOpen, setGrantOpen] = useState(false)
  const [editForm] = Form.useForm<EditForm>()
  const [grantForm] = Form.useForm<GrantForm>()
  const knowledgeBase = useQuery({
    queryKey: ['knowledge-base', tenantId, knowledgeBaseId],
    queryFn: () => api.getKnowledgeBase(tenantId, knowledgeBaseId),
    enabled: Number.isSafeInteger(knowledgeBaseId) && knowledgeBaseId > 0,
  })
  const applications = useQuery({
    queryKey: ['applications', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listApplications(tenantId, page, size)),
    enabled: knowledgeBase.isSuccess,
  })
  const grants = useQuery({
    queryKey: ['grants', tenantId, 'knowledge-base', knowledgeBaseId],
    queryFn: () => collectAllPages((page, size) => api.listGrantsByKnowledgeBase(tenantId, knowledgeBaseId, page, size)),
    enabled: knowledgeBase.isSuccess,
  })
  useEffect(() => {
    if (knowledgeBase.data) editForm.setFieldsValue({
      name: knowledgeBase.data.name,
      description: knowledgeBase.data.description ?? '',
    })
  }, [editForm, knowledgeBase.data])

  const update = useMutation({
    mutationFn: (body: Partial<EditForm> & { status?: '1' | '2' }) =>
      api.updateKnowledgeBase(tenantId, knowledgeBaseId, body),
    onSuccess: async () => {
      setEditing(false)
      message.success('知识库已更新')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['knowledge-base', tenantId, knowledgeBaseId] }),
        queryClient.invalidateQueries({ queryKey: ['knowledge-bases', tenantId] }),
      ])
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const upsertGrant = useMutation({
    mutationFn: (values: GrantForm) => api.upsertGrant(tenantId, values.applicationId, knowledgeBaseId, values.permission),
    onSuccess: async () => {
      setGrantOpen(false)
      grantForm.resetFields()
      message.success('应用授权已更新')
      await queryClient.invalidateQueries({ queryKey: ['grants', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const revokeGrant = useMutation({
    mutationFn: (applicationId: number) => api.revokeGrant(tenantId, applicationId, knowledgeBaseId),
    onSuccess: async () => {
      message.success('应用授权已撤销')
      await queryClient.invalidateQueries({ queryKey: ['grants', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const applicationById = useMemo(() => new Map(
    (applications.data ?? []).map((application) => [application.id, application]),
  ), [applications.data])

  if (!Number.isSafeInteger(knowledgeBaseId) || knowledgeBaseId <= 0) return <ErrorState error={new Error('Invalid knowledge base id')} />
  if (knowledgeBase.isLoading) return <PageLoading />
  if (knowledgeBase.isError) return <ErrorState error={knowledgeBase.error} onRetry={() => knowledgeBase.refetch()} />
  if (!knowledgeBase.data) return null
  const data = knowledgeBase.data
  const disabling = data.status === '1'

  const columns = [
    {
      title: '应用',
      render: (_: unknown, grant: ApplicationGrant) => {
        const application = applicationById.get(grant.applicationId)
        return (
          <Space orientation="vertical" size={0}>
            <Link to={`/applications/${grant.applicationId}`}><Typography.Text strong>{application?.name ?? `应用 #${grant.applicationId}`}</Typography.Text></Link>
            <Typography.Text type="secondary">{application ? `${application.code} · ${environmentLabel(application.environment)}` : `Application ID：${grant.applicationId}`}</Typography.Text>
          </Space>
        )
      },
    },
    { title: '权限', dataIndex: 'permission', render: (permission: GrantPermission) => <Tag color="blue">{permissionLabel(permission)}</Tag> },
    { title: '状态', dataIndex: 'status', render: (status: ApplicationGrant['status']) => <StatusTag status={status} /> },
    { title: '授权时间', dataIndex: 'grantedAt', render: formatDateTime },
    {
      title: '操作',
      render: (_: unknown, grant: ApplicationGrant) => (
        <Space>
          <Button type="link" onClick={() => { grantForm.setFieldsValue({ applicationId: grant.applicationId, permission: grant.permission }); setGrantOpen(true) }}>修改</Button>
          {grant.status === '1' && (
            <Popconfirm title="撤销这个应用授权？" description="该应用对当前知识库的新查询将立即被拒绝。" okText="确认撤销" cancelText="取消" onConfirm={() => revokeGrant.mutate(grant.applicationId)}>
              <Button danger type="link">撤销</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        title={data.name}
        description={`KnowledgeBase ID：${data.id}`}
        crumbs={[{ title: '知识库管理', to: '/knowledge-bases' }, { title: data.name }]}
        action={(
          <Space>
            <Button icon={<CopyOutlined />} onClick={() => navigator.clipboard.writeText(String(data.id)).then(() => message.success('KnowledgeBase ID 已复制'))}>复制 ID</Button>
            <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>编辑</Button>
            <Popconfirm
              title={disabling ? '停用这个知识库？' : '重新启用这个知识库？'}
              description={disabling ? '停用后所有应用对该知识库的新查询都会被拒绝，不删除已有内容。' : '启用后仍需有效应用、Credential 和 Grant 才能查询。'}
              okText={disabling ? '确认停用' : '确认启用'}
              cancelText="取消"
              onConfirm={() => update.mutate({ status: disabling ? '2' : '1' })}
            >
              <Button danger={disabling} icon={disabling ? <PauseCircleOutlined /> : <PlayCircleOutlined />}>
                {disabling ? '停用知识库' : '启用知识库'}
              </Button>
            </Popconfirm>
          </Space>
        )}
      />
      {data.status === '2' && <Alert showIcon type="warning" title="知识库已停用" description="所有应用对该知识库的新查询都会被拒绝；已有内容不会被删除。" className="page-alert" />}
      <Card className="detail-summary-card">
        <Descriptions column={3}>
          <Descriptions.Item label="状态"><StatusTag status={data.status} /></Descriptions.Item>
          <Descriptions.Item label="Tenant ID">{data.tenantId}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{formatDateTime(data.updatedAt)}</Descriptions.Item>
          <Descriptions.Item label="描述" span={3}>{data.description || '暂无描述'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title={<Space><FileTextOutlined />文档管理</Space>} className="detail-summary-card">
        <DocumentListPanel tenantId={tenantId} knowledgeBaseId={knowledgeBaseId} />
      </Card>
      <Card title={<Space><LinkOutlined />已授权应用</Space>} extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => { grantForm.resetFields(); setGrantOpen(true) }}>添加应用授权</Button>}>
        <Alert showIcon type="info" title="授权主体是可信应用" description="业务系统仍需自行判断最终用户能否查看具体业务数据。" className="section-alert" />
        {grants.isLoading ? <PageLoading /> : grants.isError ? <ErrorState error={grants.error} onRetry={() => grants.refetch()} /> : (grants.data?.length ?? 0) === 0 ? <EmptyState description="尚未授权任何应用" /> : (
          <Table rowKey="id" columns={columns} dataSource={grants.data} pagination={{ pageSize: 10 }} />
        )}
      </Card>

      <Modal open={editing} title="编辑知识库" okText="保存" cancelText="取消" confirmLoading={update.isPending} onCancel={() => setEditing(false)} onOk={() => editForm.submit()}>
        <Form form={editForm} layout="vertical" onFinish={(values) => update.mutate(values)}>
          <Form.Item name="name" label="知识库名称" rules={[{ required: true }, { max: 200 }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 1000 }]}><Input.TextArea rows={4} maxLength={1000} showCount /></Form.Item>
        </Form>
      </Modal>
      <Modal open={grantOpen} title="配置应用授权" okText="保存授权" cancelText="取消" confirmLoading={upsertGrant.isPending} onCancel={() => { setGrantOpen(false); grantForm.resetFields() }} onOk={() => grantForm.submit()} destroyOnHidden>
        <Form form={grantForm} layout="vertical" initialValues={{ permission: '1' }} onFinish={(values) => upsertGrant.mutate(values)}>
          <Form.Item name="applicationId" label="应用" rules={[{ required: true }]}>
            <Select showSearch optionFilterProp="label" options={(applications.data ?? []).map((application) => ({ value: application.id, label: `${application.name} · ${application.code} (#${application.id})` }))} />
          </Form.Item>
          <Form.Item name="permission" label="权限" rules={[{ required: true }]}>
            <Select options={[
              { value: '1', label: '只读（Retrieve / Answer）' },
              { value: '2', label: '只写' },
              { value: '3', label: '读写' },
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
