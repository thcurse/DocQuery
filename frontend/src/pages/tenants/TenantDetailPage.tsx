import {
  EditOutlined,
  KeyOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
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
  Space,
  Table,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { StatusTag } from '../../components/StatusTag'
import { formatDateTime } from '../../domain/display'
import type { TenantAdministrator } from '../../domain/types'
import { queryClient } from '../../queryClient'

interface AdministratorForm {
  loginName: string
  password: string
}

interface PasswordForm {
  password: string
}

const passwordRules = [
  { required: true },
  { min: 12, message: '密码不能少于 12 个字符' },
  {
    validator: (_: unknown, value?: string) => !value || new TextEncoder().encode(value).length <= 72
      ? Promise.resolve()
      : Promise.reject(new Error('密码不能超过 72 UTF-8 字节')),
  },
]

export function TenantDetailPage() {
  const tenantId = Number(useParams().tenantId)
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [creatingAdministrator, setCreatingAdministrator] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [resetTarget, setResetTarget] = useState<TenantAdministrator | null>(null)
  const [resettingPassword, setResettingPassword] = useState(false)
  const [form] = Form.useForm<{ name: string }>()
  const [administratorForm] = Form.useForm<AdministratorForm>()
  const [passwordForm] = Form.useForm<PasswordForm>()

  const validTenantId = Number.isSafeInteger(tenantId) && tenantId > 0
  const tenant = useQuery({
    queryKey: ['tenant', tenantId],
    queryFn: () => api.getTenant(tenantId),
    enabled: validTenantId,
  })
  const administrators = useQuery({
    queryKey: ['tenant-administrators', tenantId],
    queryFn: () => collectAllPages((page, size) =>
      api.listTenantAdministrators(tenantId, page, size)),
    enabled: validTenantId,
  })

  useEffect(() => {
    if (tenant.data) form.setFieldsValue({ name: tenant.data.name })
  }, [form, tenant.data])

  const update = useMutation({
    mutationFn: (body: { name?: string; status?: '1' | '2' }) => api.updateTenant(tenantId, body),
    onSuccess: async () => {
      setEditing(false)
      message.success('租户已更新')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['tenant', tenantId] }),
        queryClient.invalidateQueries({ queryKey: ['tenants'] }),
      ])
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const updateAdministrator = useMutation({
    mutationFn: ({ administratorId, status }: { administratorId: number; status: '1' | '2' }) =>
      api.updateTenantAdministrator(tenantId, administratorId, { status }),
    onSuccess: async (administrator) => {
      message.success(administrator.status === '1' ? '管理员已启用' : '管理员已停用')
      await queryClient.invalidateQueries({ queryKey: ['tenant-administrators', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const activeAdministratorCount = useMemo(
    () => (administrators.data ?? []).filter((item) => item.status === '1').length,
    [administrators.data],
  )

  const createAdministrator = async (values: AdministratorForm) => {
    setCreatingAdministrator(true)
    try {
      await api.createTenantAdministrator(tenantId, values)
      administratorForm.resetFields()
      setCreateOpen(false)
      message.success('租户管理员已创建')
      await queryClient.invalidateQueries({ queryKey: ['tenant-administrators', tenantId] })
    } catch (error) {
      message.error(errorMessage(error))
    } finally {
      setCreatingAdministrator(false)
    }
  }

  const resetPassword = async (values: PasswordForm) => {
    if (!resetTarget) return
    setResettingPassword(true)
    try {
      await api.resetTenantAdministratorPassword(tenantId, resetTarget.id, values.password)
      passwordForm.resetFields()
      setResetTarget(null)
      message.success('密码已重置，管理员原有会话已失效')
      await queryClient.invalidateQueries({ queryKey: ['tenant-administrators', tenantId] })
    } catch (error) {
      message.error(errorMessage(error))
    } finally {
      setResettingPassword(false)
    }
  }

  if (!validTenantId) {
    return <ErrorState error={new Error('Invalid tenant id')} onRetry={() => navigate('/tenants')} />
  }
  if (tenant.isLoading) return <PageLoading />
  if (tenant.isError) return <ErrorState error={tenant.error} onRetry={() => tenant.refetch()} />
  if (!tenant.data) return null

  const data = tenant.data
  const disabling = data.status === '1'
  const administratorColumns = [
    {
      title: '管理员账号',
      key: 'administrator',
      render: (_: unknown, administrator: TenantAdministrator) => (
        <Space orientation="vertical" size={0}>
          <Typography.Text strong>{administrator.loginName}</Typography.Text>
          <Typography.Text type="secondary">Administrator ID：{administrator.id}</Typography.Text>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', render: (status: TenantAdministrator['status']) => <StatusTag status={status} /> },
    { title: '创建时间', dataIndex: 'createdAt', render: formatDateTime },
    { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, administrator: TenantAdministrator) => {
        const isActive = administrator.status === '1'
        const lastActive = isActive && activeAdministratorCount <= 1
        return (
          <Space wrap>
            <Button size="small" icon={<KeyOutlined />} onClick={() => setResetTarget(administrator)}>
              重置密码
            </Button>
            <Tooltip title={lastActive ? '必须至少保留一个有效租户管理员' : undefined}>
              <span>
                <Popconfirm
                  disabled={lastActive}
                  title={isActive ? '停用这个管理员？' : '启用这个管理员？'}
                  description={isActive
                    ? '停用后该管理员的既有会话会立即失效。'
                    : '启用后管理员可在所属 Tenant 启用时重新登录。'}
                  okText={isActive ? '确认停用' : '确认启用'}
                  cancelText="取消"
                  onConfirm={() => updateAdministrator.mutate({
                    administratorId: administrator.id,
                    status: isActive ? '2' : '1',
                  })}
                >
                  <Button
                    size="small"
                    danger={isActive}
                    disabled={lastActive}
                    icon={isActive ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
                  >
                    {isActive ? '停用' : '启用'}
                  </Button>
                </Popconfirm>
              </span>
            </Tooltip>
          </Space>
        )
      },
    },
  ]

  return (
    <>
      <PageHeader
        title={data.name}
        description={`Tenant ID：${data.id}`}
        crumbs={[{ title: '租户管理', to: '/tenants' }, { title: data.name }]}
        action={(
          <Space>
            <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>修改名称</Button>
            <Popconfirm
              title={disabling ? '停用这个租户？' : '重新启用这个租户？'}
              description={disabling
                ? '停用会使该租户既有管理员 Session 失效，并阻止应用继续访问知识库。'
                : '启用后租户管理员和获授权应用可按现有状态恢复访问。'}
              okText={disabling ? '确认停用' : '确认启用'}
              cancelText="取消"
              onConfirm={() => update.mutate({ status: disabling ? '2' : '1' })}
            >
              <Button danger={disabling} icon={disabling ? <PauseCircleOutlined /> : <PlayCircleOutlined />}>
                {disabling ? '停用租户' : '启用租户'}
              </Button>
            </Popconfirm>
          </Space>
        )}
      />
      {data.status === '2' && (
        <Alert showIcon type="warning" title="租户已停用" description="该租户的管理员和应用访问均被阻止；平台管理员仍可维护管理员账号。" className="page-alert" />
      )}
      <Card title="租户信息" className="detail-summary-card">
        <Descriptions column={2} bordered>
          <Descriptions.Item label="名称">{data.name}</Descriptions.Item>
          <Descriptions.Item label="状态"><StatusTag status={data.status} /></Descriptions.Item>
          <Descriptions.Item label="Tenant ID">{data.id}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatDateTime(data.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间" span={2}>{formatDateTime(data.updatedAt)}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card
        title="租户管理员"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新增管理员</Button>}
      >
        <Alert
          showIcon
          type="info"
          title="账号由平台统一配置"
          description="租户管理员没有公开注册入口。密码只用于本次提交，不回显；停用或重置密码会使既有会话失效。"
          className="section-alert"
        />
        {administrators.isLoading ? <PageLoading /> : administrators.isError ? (
          <ErrorState error={administrators.error} onRetry={() => administrators.refetch()} />
        ) : (
          <Table
            rowKey="id"
            columns={administratorColumns}
            dataSource={administrators.data ?? []}
            pagination={{ pageSize: 10, showSizeChanger: true }}
          />
        )}
      </Card>

      <Modal
        open={editing}
        title="修改租户名称"
        okText="保存"
        cancelText="取消"
        confirmLoading={update.isPending}
        onCancel={() => setEditing(false)}
        onOk={() => form.submit()}
      >
        <Form form={form} layout="vertical" onFinish={(values) => update.mutate(values)}>
          <Form.Item name="name" label="租户名称" rules={[{ required: true }, { max: 200 }]}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={createOpen}
        title="新增租户管理员"
        okText="创建管理员"
        cancelText="取消"
        confirmLoading={creatingAdministrator}
        onCancel={() => { setCreateOpen(false); administratorForm.resetFields() }}
        onOk={() => administratorForm.submit()}
        destroyOnHidden
      >
        <Form form={administratorForm} layout="vertical" requiredMark={false} onFinish={createAdministrator}>
          <Form.Item
            name="loginName"
            label="管理员登录名"
            extra="全平台唯一；3—64 位小写字母、数字、点、下划线或连字符"
            rules={[
              { required: true },
              { pattern: /^[a-z0-9][a-z0-9._-]{2,63}$/, message: '登录名格式不正确' },
            ]}
          >
            <Input autoComplete="off" placeholder="例如：nebula.admin2" />
          </Form.Item>
          <Form.Item name="password" label="初始密码" extra="至少 12 个字符，最多 72 UTF-8 字节" rules={passwordRules}>
            <Input.Password autoComplete="new-password" placeholder="通过受控渠道交付" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={resetTarget !== null}
        title={`重置密码${resetTarget ? `：${resetTarget.loginName}` : ''}`}
        okText="设置新密码"
        cancelText="取消"
        confirmLoading={resettingPassword}
        onCancel={() => { setResetTarget(null); passwordForm.resetFields() }}
        onOk={() => passwordForm.submit()}
        destroyOnHidden
      >
        <Alert
          showIcon
          type="warning"
          title="旧密码和既有会话将失效"
          description="新密码不会回显，请在提交前确认已通过受控渠道安排交付。"
          className="modal-alert"
        />
        <Form form={passwordForm} layout="vertical" requiredMark={false} onFinish={resetPassword}>
          <Form.Item name="password" label="新密码" extra="至少 12 个字符，最多 72 UTF-8 字节" rules={passwordRules}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
