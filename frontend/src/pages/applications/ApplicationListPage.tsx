import { AppstoreAddOutlined, SearchOutlined } from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Button, Card, Form, Input, Modal, Select, Space, Table, Typography, message } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { EmptyState, ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { StatusTag } from '../../components/StatusTag'
import { environmentLabel, formatDateTime } from '../../domain/display'
import type { Application, ApplicationEnvironment } from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'
import { queryClient } from '../../queryClient'

interface CreateForm {
  code: string
  name: string
  environment: ApplicationEnvironment
  description?: string
}

const environments: ApplicationEnvironment[] = ['DEVELOPMENT', 'TESTING', 'PRODUCTION']

export function ApplicationListPage() {
  const tenantId = useTenantId()
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm<CreateForm>()
  const applications = useQuery({
    queryKey: ['applications', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listApplications(tenantId, page, size)),
  })
  const create = useMutation({
    mutationFn: (body: CreateForm) => api.createApplication(tenantId, body),
    onSuccess: async () => {
      setOpen(false)
      form.resetFields()
      message.success('应用已创建')
      await queryClient.invalidateQueries({ queryKey: ['applications', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return applications.data ?? []
    return (applications.data ?? []).filter((application) =>
      application.name.toLowerCase().includes(term)
      || application.code.toLowerCase().includes(term)
      || String(application.id).includes(term),
    )
  }, [applications.data, search])

  const columns = [
    {
      title: '应用',
      render: (_: unknown, application: Application) => (
        <Space orientation="vertical" size={0}>
          <Link to={`/applications/${application.id}`}><Typography.Text strong>{application.name}</Typography.Text></Link>
          <Typography.Text type="secondary">{application.code} · Application ID：{application.id}</Typography.Text>
        </Space>
      ),
    },
    { title: '环境', dataIndex: 'environment', render: environmentLabel },
    { title: '状态', dataIndex: 'status', render: (status: Application['status']) => <StatusTag status={status} /> },
    { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
    { title: '操作', render: (_: unknown, application: Application) => <Link to={`/applications/${application.id}`}>管理接入</Link> },
  ]

  return (
    <>
      <PageHeader
        title="应用管理"
        description="应用代表可信业务系统后端，不代表最终业务用户。凭证和知识库授权均挂在应用上。"
        action={<Button type="primary" icon={<AppstoreAddOutlined />} onClick={() => setOpen(true)}>创建应用</Button>}
      />
      <Card>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="按应用名称、编码或 Application ID 筛选"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          className="list-search"
        />
        {applications.isLoading ? <PageLoading /> : applications.isError ? (
          <ErrorState error={applications.error} onRetry={() => applications.refetch()} />
        ) : filtered.length === 0 ? <EmptyState description="没有符合条件的应用" /> : (
          <Table rowKey="id" columns={columns} dataSource={filtered} pagination={{ pageSize: 10, showSizeChanger: true }} />
        )}
      </Card>
      <Modal open={open} title="创建应用" okText="创建应用" cancelText="取消" confirmLoading={create.isPending} onCancel={() => { setOpen(false); form.resetFields() }} onOk={() => form.submit()} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} initialValues={{ environment: 'PRODUCTION' }} onFinish={(values) => create.mutate(values)}>
          <Form.Item
            name="code"
            label="应用编码"
            extra="创建后不可修改；3—64 位小写字母、数字或连字符"
            rules={[
              { required: true },
              { pattern: /^[a-z0-9][a-z0-9-]{2,63}$/, message: '应用编码格式不正确' },
            ]}
          >
            <Input placeholder="例如：ticket-service" />
          </Form.Item>
          <Form.Item name="name" label="应用名称" rules={[{ required: true }, { max: 200 }]}>
            <Input placeholder="例如：售后工单系统" />
          </Form.Item>
          <Form.Item name="environment" label="环境" rules={[{ required: true }]}>
            <Select options={environments.map((value) => ({ value, label: environmentLabel(value) }))} />
          </Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 1000 }]}>
            <Input.TextArea rows={3} showCount maxLength={1000} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
