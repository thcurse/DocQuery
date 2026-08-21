import { PlusOutlined, SearchOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Typography,
  message,
} from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { EmptyState, ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { StatusTag } from '../../components/StatusTag'
import { formatDateTime } from '../../domain/display'
import type { Tenant } from '../../domain/types'
import { queryClient } from '../../queryClient'

interface CreateTenantForm {
  tenantName: string
  adminLoginName: string
  adminPassword: string
}

export function TenantListPage() {
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [createdAdmin, setCreatedAdmin] = useState<string | null>(null)
  const [form] = Form.useForm<CreateTenantForm>()
  const tenants = useQuery({
    queryKey: ['tenants', 'all'],
    queryFn: () => collectAllPages(api.listTenants),
  })

  const createTenant = async (values: CreateTenantForm) => {
    setCreating(true)
    try {
      // 初始密码不进入 TanStack Query/Mutation Cache。
      const result = await api.createTenant(values)
      setCreatedAdmin(result.initialAdmin.loginName)
      form.resetFields()
      setOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['tenants'] })
    } catch (error) {
      message.error(errorMessage(error))
    } finally {
      setCreating(false)
    }
  }

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return tenants.data ?? []
    return (tenants.data ?? []).filter((tenant) =>
      tenant.name.toLowerCase().includes(term) || String(tenant.id).includes(term),
    )
  }, [search, tenants.data])

  const columns = [
    {
      title: '租户',
      key: 'tenant',
      render: (_: unknown, tenant: Tenant) => (
        <Space orientation="vertical" size={0}>
          <Link to={`/tenants/${tenant.id}`}><Typography.Text strong>{tenant.name}</Typography.Text></Link>
          <Typography.Text type="secondary">Tenant ID：{tenant.id}</Typography.Text>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', render: (status: Tenant['status']) => <StatusTag status={status} /> },
    { title: '创建时间', dataIndex: 'createdAt', render: formatDateTime },
    { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
    { title: '操作', render: (_: unknown, tenant: Tenant) => <Link to={`/tenants/${tenant.id}`}>查看详情</Link> },
  ]

  return (
    <>
      <PageHeader
        title="租户管理"
        description="创建并维护 DocQuery 的租户隔离边界。租户管理员只能访问自身租户。"
        action={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>创建租户</Button>}
      />
      {createdAdmin && (
        <Alert
          closable
          showIcon
          type="success"
          title="租户创建成功"
          description={`首个租户管理员 ${createdAdmin} 已创建。请通过受控渠道交付刚才录入的密码；DocQuery 不会回传或保存明文密码。`}
          onClose={() => setCreatedAdmin(null)}
          className="page-alert"
        />
      )}
      <Card>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="按租户名称或 Tenant ID 筛选"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          className="list-search"
        />
        {tenants.isLoading ? <PageLoading /> : tenants.isError ? (
          <ErrorState error={tenants.error} onRetry={() => tenants.refetch()} />
        ) : filtered.length === 0 ? <EmptyState description="没有符合条件的租户" /> : (
          <Table rowKey="id" columns={columns} dataSource={filtered} pagination={{ pageSize: 10, showSizeChanger: true }} />
        )}
      </Card>
      <Modal
        open={open}
        title="创建租户"
        okText="创建租户"
        cancelText="取消"
        confirmLoading={creating}
        onCancel={() => { setOpen(false); form.resetFields() }}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Alert
          showIcon
          type="info"
          title="同时创建首个租户管理员"
          description="管理员密码只用于本次提交，不会在创建结果中回传。"
          className="modal-alert"
        />
        <Form form={form} layout="vertical" requiredMark={false} onFinish={createTenant}>
          <Form.Item name="tenantName" label="租户名称" rules={[{ required: true }, { max: 200 }]}>
            <Input placeholder="例如：星云科技" />
          </Form.Item>
          <Form.Item
            name="adminLoginName"
            label="管理员登录名"
            extra="3—64 位小写字母、数字、点、下划线或连字符"
            rules={[
              { required: true },
              { pattern: /^[a-z0-9][a-z0-9._-]{2,63}$/, message: '登录名格式不正确' },
            ]}
          >
            <Input autoComplete="off" placeholder="例如：nebula.admin" />
          </Form.Item>
          <Form.Item
            name="adminPassword"
            label="初始密码"
            extra="至少 12 个字符，最多 72 UTF-8 字节"
            rules={[
              { required: true },
              { min: 12, message: '密码不能少于 12 个字符' },
              {
                validator: (_, value?: string) => !value || new TextEncoder().encode(value).length <= 72
                  ? Promise.resolve()
                  : Promise.reject(new Error('密码不能超过 72 UTF-8 字节')),
              },
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="录入后通过受控渠道交付" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
