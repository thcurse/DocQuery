import {
  CopyOutlined,
  EditOutlined,
  KeyOutlined,
  LinkOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { EmptyState, ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { SecretModal } from '../../components/SecretModal'
import { StatusTag } from '../../components/StatusTag'
import { environmentLabel, formatDateTime, permissionLabel } from '../../domain/display'
import type {
  ApplicationEnvironment,
  ApplicationGrant,
  Credential,
  GrantPermission,
} from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'
import { queryClient } from '../../queryClient'

interface EditForm {
  name: string
  environment: ApplicationEnvironment
  description?: string
}

interface GrantForm {
  knowledgeBaseId: number
  permission: GrantPermission
}

export function ApplicationDetailPage() {
  const tenantId = useTenantId()
  const applicationId = Number(useParams().applicationId)
  const [editing, setEditing] = useState(false)
  const [creatingCredential, setCreatingCredential] = useState(false)
  const [credentialBusy, setCredentialBusy] = useState(false)
  const [secret, setSecret] = useState<string | null>(null)
  const [grantOpen, setGrantOpen] = useState(false)
  const [editForm] = Form.useForm<EditForm>()
  const [credentialForm] = Form.useForm<{ name: string }>()
  const [grantForm] = Form.useForm<GrantForm>()

  const application = useQuery({
    queryKey: ['application', tenantId, applicationId],
    queryFn: () => api.getApplication(tenantId, applicationId),
    enabled: Number.isSafeInteger(applicationId) && applicationId > 0,
  })
  const credentials = useQuery({
    queryKey: ['credentials', tenantId, applicationId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listCredentials(tenantId, applicationId, page, size)),
    enabled: application.isSuccess,
  })
  const grants = useQuery({
    queryKey: ['grants', tenantId, 'application', applicationId],
    queryFn: () => collectAllPages((page, size) => api.listGrantsByApplication(tenantId, applicationId, page, size)),
    enabled: application.isSuccess,
  })
  const knowledgeBases = useQuery({
    queryKey: ['knowledge-bases', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listKnowledgeBases(tenantId, page, size)),
    enabled: application.isSuccess,
  })

  useEffect(() => {
    if (application.data) editForm.setFieldsValue({
      name: application.data.name,
      environment: application.data.environment,
      description: application.data.description ?? '',
    })
  }, [application.data, editForm])

  const update = useMutation({
    mutationFn: (body: Partial<EditForm> & { status?: '1' | '2' }) =>
      api.updateApplication(tenantId, applicationId, body),
    onSuccess: async () => {
      setEditing(false)
      message.success('应用已更新')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['application', tenantId, applicationId] }),
        queryClient.invalidateQueries({ queryKey: ['applications', tenantId] }),
      ])
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const revokeCredential = useMutation({
    mutationFn: (credentialId: number) => api.revokeCredential(tenantId, applicationId, credentialId),
    onSuccess: async () => {
      message.success('凭证已撤销')
      await queryClient.invalidateQueries({ queryKey: ['credentials', tenantId, applicationId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const upsertGrant = useMutation({
    mutationFn: (values: GrantForm) => api.upsertGrant(
      tenantId, applicationId, values.knowledgeBaseId, values.permission,
    ),
    onSuccess: async () => {
      setGrantOpen(false)
      grantForm.resetFields()
      message.success('知识库授权已更新')
      await queryClient.invalidateQueries({ queryKey: ['grants', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const revokeGrant = useMutation({
    mutationFn: (knowledgeBaseId: number) => api.revokeGrant(tenantId, applicationId, knowledgeBaseId),
    onSuccess: async () => {
      message.success('知识库授权已撤销')
      await queryClient.invalidateQueries({ queryKey: ['grants', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const knowledgeBaseById = useMemo(() => new Map(
    (knowledgeBases.data ?? []).map((knowledgeBase) => [knowledgeBase.id, knowledgeBase]),
  ), [knowledgeBases.data])

  const createCredential = async ({ name }: { name: string }) => {
    setCredentialBusy(true)
    try {
      // 完整 Secret 只进入局部状态，不进入 TanStack Query/Mutation Cache。
      const created = await api.createCredential(tenantId, applicationId, name)
      setCreatingCredential(false)
      credentialForm.resetFields()
      setSecret(created.credential)
      await queryClient.invalidateQueries({ queryKey: ['credentials', tenantId, applicationId] })
    } catch (error) {
      message.error(errorMessage(error))
    } finally {
      setCredentialBusy(false)
    }
  }

  if (!Number.isSafeInteger(applicationId) || applicationId <= 0) return <ErrorState error={new Error('Invalid application id')} />
  if (application.isLoading) return <PageLoading />
  if (application.isError) return <ErrorState error={application.error} onRetry={() => application.refetch()} />
  if (!application.data) return null
  const data = application.data
  const disabling = data.status === '1'

  const credentialColumns = [
    {
      title: '凭证名称',
      render: (_: unknown, credential: Credential) => (
        <Space orientation="vertical" size={0}>
          <Typography.Text strong>{credential.name}</Typography.Text>
          <Typography.Text type="secondary">Key ID 前缀：{credential.keyIdPrefix}</Typography.Text>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', render: (status: Credential['status']) => <StatusTag status={status} /> },
    { title: '创建时间', dataIndex: 'createdAt', render: formatDateTime },
    { title: '最后使用', dataIndex: 'lastUsedAt', render: formatDateTime },
    {
      title: '操作',
      render: (_: unknown, credential: Credential) => credential.status === '1' ? (
        <Popconfirm
          title="撤销这个凭证？"
          description="撤销不可逆，新请求会立即失去认证能力。"
          okText="确认撤销"
          cancelText="取消"
          onConfirm={() => revokeCredential.mutate(credential.id)}
        >
          <Button danger type="link" icon={<StopOutlined />}>撤销</Button>
        </Popconfirm>
      ) : <Typography.Text type="secondary">不可使用</Typography.Text>,
    },
  ]

  const grantColumns = [
    {
      title: '知识库',
      render: (_: unknown, grant: ApplicationGrant) => {
        const kb = knowledgeBaseById.get(grant.knowledgeBaseId)
        return (
          <Space orientation="vertical" size={0}>
            <Link to={`/knowledge-bases/${grant.knowledgeBaseId}`}><Typography.Text strong>{kb?.name ?? `知识库 #${grant.knowledgeBaseId}`}</Typography.Text></Link>
            <Typography.Text type="secondary">KnowledgeBase ID：{grant.knowledgeBaseId}</Typography.Text>
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
          <Button type="link" onClick={() => {
            grantForm.setFieldsValue({ knowledgeBaseId: grant.knowledgeBaseId, permission: grant.permission })
            setGrantOpen(true)
          }}>修改</Button>
          {grant.status === '1' && (
            <Popconfirm title="撤销这个知识库授权？" description="该应用的新查询将立即被拒绝。" okText="确认撤销" cancelText="取消" onConfirm={() => revokeGrant.mutate(grant.knowledgeBaseId)}>
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
        description={`${data.code} · Application ID：${data.id}`}
        crumbs={[{ title: '应用管理', to: '/applications' }, { title: data.name }]}
        action={(
          <Space>
            <Button icon={<CopyOutlined />} onClick={() => navigator.clipboard.writeText(String(data.id)).then(() => message.success('Application ID 已复制'))}>复制 ID</Button>
            <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>编辑</Button>
            <Popconfirm
              title={disabling ? '停用这个应用？' : '重新启用这个应用？'}
              description={disabling ? '停用后该应用的所有 Credential 新请求都会被拒绝。' : '启用后仍需有效 Credential 和知识库 Grant 才能查询。'}
              okText={disabling ? '确认停用' : '确认启用'}
              cancelText="取消"
              onConfirm={() => update.mutate({ status: disabling ? '2' : '1' })}
            >
              <Button danger={disabling} icon={disabling ? <PauseCircleOutlined /> : <PlayCircleOutlined />}>
                {disabling ? '停用应用' : '启用应用'}
              </Button>
            </Popconfirm>
          </Space>
        )}
      />
      {data.status === '2' && <Alert showIcon type="warning" title="应用已停用" description="所有 Credential 的新服务请求都会被拒绝。" className="page-alert" />}
      <Card className="detail-summary-card">
        <Descriptions column={3}>
          <Descriptions.Item label="状态"><StatusTag status={data.status} /></Descriptions.Item>
          <Descriptions.Item label="环境">{environmentLabel(data.environment)}</Descriptions.Item>
          <Descriptions.Item label="稳定编码">{data.code}</Descriptions.Item>
          <Descriptions.Item label="描述" span={3}>{data.description || '暂无描述'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card>
        <Tabs items={[
          {
            key: 'credentials',
            label: <Space><KeyOutlined />应用凭证</Space>,
            children: (
              <Space orientation="vertical" size={16} className="full-width">
                <Alert showIcon type="info" title="Credential 只属于可信应用后端" description="完整凭证只在创建时展示一次。最终业务用户不能使用它登录管理后台。" />
                <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreatingCredential(true)}>生成凭证</Button>
                {credentials.isLoading ? <PageLoading /> : credentials.isError ? <ErrorState error={credentials.error} onRetry={() => credentials.refetch()} /> : (credentials.data?.length ?? 0) === 0 ? <EmptyState description="尚未创建应用凭证" /> : (
                  <Table rowKey="id" columns={credentialColumns} dataSource={credentials.data} pagination={{ pageSize: 10 }} />
                )}
              </Space>
            ),
          },
          {
            key: 'grants',
            label: <Space><LinkOutlined />知识库授权</Space>,
            children: (
              <Space orientation="vertical" size={16} className="full-width">
                <Alert showIcon type="info" title="Grant 控制应用可以访问哪些知识库" description="DocQuery 不管理业务系统内部的最终用户和业务数据权限。" />
                <Button type="primary" icon={<PlusOutlined />} onClick={() => { grantForm.resetFields(); setGrantOpen(true) }}>添加授权</Button>
                {grants.isLoading ? <PageLoading /> : grants.isError ? <ErrorState error={grants.error} onRetry={() => grants.refetch()} /> : (grants.data?.length ?? 0) === 0 ? <EmptyState description="该应用尚未获得知识库授权" /> : (
                  <Table rowKey="id" columns={grantColumns} dataSource={grants.data} pagination={{ pageSize: 10 }} />
                )}
              </Space>
            ),
          },
        ]} />
      </Card>

      <Modal open={editing} title="编辑应用" okText="保存" cancelText="取消" confirmLoading={update.isPending} onCancel={() => setEditing(false)} onOk={() => editForm.submit()}>
        <Form form={editForm} layout="vertical" onFinish={(values) => update.mutate(values)}>
          <Form.Item label="稳定编码"><Input value={data.code} disabled /></Form.Item>
          <Form.Item name="name" label="应用名称" rules={[{ required: true }, { max: 200 }]}><Input /></Form.Item>
          <Form.Item name="environment" label="环境" rules={[{ required: true }]}>
            <Select options={(['DEVELOPMENT', 'TESTING', 'PRODUCTION'] as ApplicationEnvironment[]).map((value) => ({ value, label: environmentLabel(value) }))} />
          </Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 1000 }]}><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>
        </Form>
      </Modal>

      <Modal open={creatingCredential} title="生成应用凭证" okText="生成凭证" cancelText="取消" confirmLoading={credentialBusy} onCancel={() => { setCreatingCredential(false); credentialForm.resetFields() }} onOk={() => credentialForm.submit()} destroyOnHidden>
        <Alert showIcon type="warning" title="凭证创建后只完整展示一次" description="请先准备好受控的密钥管理位置。" className="modal-alert" />
        <Form form={credentialForm} layout="vertical" onFinish={createCredential}>
          <Form.Item name="name" label="凭证名称" rules={[{ required: true }, { max: 200 }]}><Input placeholder="例如：生产环境主凭证" /></Form.Item>
        </Form>
      </Modal>

      <Modal open={grantOpen} title="配置知识库授权" okText="保存授权" cancelText="取消" confirmLoading={upsertGrant.isPending} onCancel={() => { setGrantOpen(false); grantForm.resetFields() }} onOk={() => grantForm.submit()} destroyOnHidden>
        <Form form={grantForm} layout="vertical" initialValues={{ permission: '1' }} onFinish={(values) => upsertGrant.mutate(values)}>
          <Form.Item name="knowledgeBaseId" label="知识库" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              disabled={grantForm.getFieldValue('knowledgeBaseId') !== undefined && grants.data?.some((grant) => grant.knowledgeBaseId === grantForm.getFieldValue('knowledgeBaseId'))}
              options={(knowledgeBases.data ?? []).map((knowledgeBase) => ({ value: knowledgeBase.id, label: `${knowledgeBase.name} (#${knowledgeBase.id})` }))}
            />
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
      <SecretModal secret={secret} onClose={() => setSecret(null)} />
    </>
  )
}
