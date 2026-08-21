import { DatabaseOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Button, Card, Form, Input, Modal, Space, Table, Typography, message } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { EmptyState, ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { StatusTag } from '../../components/StatusTag'
import { formatDateTime } from '../../domain/display'
import type { KnowledgeBase } from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'
import { queryClient } from '../../queryClient'

interface CreateForm {
  name: string
  description?: string
}

export function KnowledgeBaseListPage() {
  const tenantId = useTenantId()
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm<CreateForm>()
  const knowledgeBases = useQuery({
    queryKey: ['knowledge-bases', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listKnowledgeBases(tenantId, page, size)),
  })
  const create = useMutation({
    mutationFn: (body: CreateForm) => api.createKnowledgeBase(tenantId, body),
    onSuccess: async () => {
      setOpen(false)
      form.resetFields()
      message.success('知识库已创建')
      await queryClient.invalidateQueries({ queryKey: ['knowledge-bases', tenantId] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase()
    if (!term) return knowledgeBases.data ?? []
    return (knowledgeBases.data ?? []).filter((knowledgeBase) =>
      knowledgeBase.name.toLowerCase().includes(term) || String(knowledgeBase.id).includes(term),
    )
  }, [knowledgeBases.data, search])

  const columns = [
    {
      title: '知识库',
      render: (_: unknown, knowledgeBase: KnowledgeBase) => (
        <Space orientation="vertical" size={0}>
          <Link to={`/knowledge-bases/${knowledgeBase.id}`}><Typography.Text strong>{knowledgeBase.name}</Typography.Text></Link>
          <Typography.Text type="secondary">KnowledgeBase ID：{knowledgeBase.id}</Typography.Text>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', render: (status: KnowledgeBase['status']) => <StatusTag status={status} /> },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (value: string | null) => value || '暂无描述' },
    { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
    { title: '操作', render: (_: unknown, knowledgeBase: KnowledgeBase) => <Link to={`/knowledge-bases/${knowledgeBase.id}`}>查看授权</Link> },
  ]

  return (
    <>
      <PageHeader
        title="知识库管理"
        description="知识库是业务资料与查询授权的稳定边界。N4.3 管理元数据和应用授权。"
        action={<Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>创建知识库</Button>}
      />
      <Card>
        <Input allowClear prefix={<SearchOutlined />} placeholder="按知识库名称或 KnowledgeBase ID 筛选" value={search} onChange={(event) => setSearch(event.target.value)} className="list-search" />
        {knowledgeBases.isLoading ? <PageLoading /> : knowledgeBases.isError ? (
          <ErrorState error={knowledgeBases.error} onRetry={() => knowledgeBases.refetch()} />
        ) : filtered.length === 0 ? <EmptyState description="没有符合条件的知识库" /> : (
          <Table rowKey="id" columns={columns} dataSource={filtered} pagination={{ pageSize: 10, showSizeChanger: true }} />
        )}
      </Card>
      <Modal open={open} title={<Space><DatabaseOutlined />创建知识库</Space>} okText="创建知识库" cancelText="取消" confirmLoading={create.isPending} onCancel={() => { setOpen(false); form.resetFields() }} onOk={() => form.submit()} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} onFinish={(values) => create.mutate(values)}>
          <Form.Item name="name" label="知识库名称" rules={[{ required: true }, { max: 200 }]}><Input placeholder="例如：售后维修手册" /></Form.Item>
          <Form.Item name="description" label="描述" rules={[{ max: 1000 }]}><Input.TextArea rows={4} maxLength={1000} showCount /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}
