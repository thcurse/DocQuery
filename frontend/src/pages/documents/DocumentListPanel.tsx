import { CopyOutlined, FileAddOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
  Upload,
  message,
} from 'antd'
import type { UploadFile } from 'antd'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../../api/client'
import { DocumentStatusTag, VersionStatusTag } from '../../components/WorkflowStatusTag'
import { EmptyState, ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { formatDateTime } from '../../domain/display'
import type { DocumentManagement } from '../../domain/types'
import { queryClient } from '../../queryClient'

interface UploadForm {
  name: string
  file: UploadFile[]
}

const acceptedExtensions = '.pdf,.docx,.txt,.md,.markdown'
const maxFileBytes = 50 * 1024 * 1024

export function DocumentListPanel({ tenantId, knowledgeBaseId }: {
  tenantId: number
  knowledgeBaseId: number
}) {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [name, setName] = useState('')
  const [documentStatus, setDocumentStatus] = useState<string>()
  const [latestVersionStatus, setLatestVersionStatus] = useState<string>()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [form] = Form.useForm<UploadForm>()

  const documents = useQuery({
    queryKey: ['documents', tenantId, knowledgeBaseId, page, size, name, documentStatus, latestVersionStatus],
    queryFn: () => api.listDocuments(tenantId, knowledgeBaseId, {
      page,
      size,
      name: name.trim() || undefined,
      documentStatus,
      latestVersionStatus,
    }),
    refetchInterval: (query) => query.state.data?.items.some((document) =>
      document.documentStatus === '2' || document.latestVersion?.status === '1',
    ) ? 3000 : false,
  })

  const upload = useMutation({
    mutationFn: async (values: UploadForm) => {
      const file = values.file[0]?.originFileObj
      if (!file) throw new Error('请选择文件')
      if (file.size > maxFileBytes) throw new Error('文件不能超过 50 MiB')
      return api.uploadDocument(
        tenantId,
        knowledgeBaseId,
        values.name.trim(),
        file,
        crypto.randomUUID(),
      )
    },
    onSuccess: async (accepted) => {
      setUploadOpen(false)
      form.resetFields()
      message.success(`文档已受理，处理任务 #${accepted.processingJobId}`)
      await queryClient.invalidateQueries({ queryKey: ['documents', tenantId, knowledgeBaseId] })
    },
    onError: (error) => message.error(error instanceof Error ? error.message : errorMessage(error)),
  })

  const columns = useMemo(() => [
    {
      title: '文档',
      render: (_: unknown, document: DocumentManagement) => (
        <Space orientation="vertical" size={0}>
          <Link to={`/knowledge-bases/${knowledgeBaseId}/documents/${document.documentId}`}>
            <Typography.Text strong>{document.name}</Typography.Text>
          </Link>
          <Typography.Text type="secondary">
            Document ID：{document.documentId}
            <Button
              type="text"
              size="small"
              aria-label={`复制 Document ID ${document.documentId}`}
              icon={<CopyOutlined />}
              onClick={() => navigator.clipboard.writeText(String(document.documentId)).then(() => message.success('Document ID 已复制'))}
            />
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '文档状态',
      dataIndex: 'documentStatus',
      render: (status: DocumentManagement['documentStatus']) => <DocumentStatusTag status={status} />,
    },
    {
      title: '当前生效版本',
      render: (_: unknown, document: DocumentManagement) => document.activeVersion
        ? <Space><span>v{document.activeVersion.versionNo}</span><VersionStatusTag status={document.activeVersion.status} /></Space>
        : <Typography.Text type="secondary">暂无</Typography.Text>,
    },
    {
      title: '最新版本',
      render: (_: unknown, document: DocumentManagement) => document.latestVersion
        ? <Space><span>v{document.latestVersion.versionNo}</span><VersionStatusTag status={document.latestVersion.status} /></Space>
        : '—',
    },
    { title: '更新时间', dataIndex: 'updatedAt', render: formatDateTime },
    {
      title: '操作',
      render: (_: unknown, document: DocumentManagement) => (
        <Link to={`/knowledge-bases/${knowledgeBaseId}/documents/${document.documentId}`}>查看详情</Link>
      ),
    },
  ], [knowledgeBaseId])

  return (
    <>
      <Alert
        showIcon
        type="info"
        title="文档按版本异步处理"
        description="上传后可离开页面；只有 READY 版本会原子切换为查询使用的 activeVersion。"
        className="section-alert"
      />
      <Space wrap className="document-toolbar">
        <Input.Search
          allowClear
          placeholder="按文档名称筛选"
          defaultValue={name}
          onSearch={(value) => { setName(value); setPage(0) }}
          style={{ width: 280 }}
        />
        <Select
          allowClear
          placeholder="文档状态"
          value={documentStatus}
          onChange={(value) => { setDocumentStatus(value); setPage(0) }}
          style={{ width: 140 }}
          options={[
            { value: '1', label: '可用' },
            { value: '2', label: '删除中' },
            { value: '3', label: '已删除' },
          ]}
        />
        <Select
          allowClear
          placeholder="最新版本状态"
          value={latestVersionStatus}
          onChange={(value) => { setLatestVersionStatus(value); setPage(0) }}
          style={{ width: 160 }}
          options={[
            { value: '1', label: '处理中' },
            { value: '2', label: '已就绪' },
            { value: '3', label: '失败' },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => documents.refetch()}>刷新</Button>
        <Button type="primary" icon={<FileAddOutlined />} onClick={() => setUploadOpen(true)}>上传文档</Button>
      </Space>

      {documents.isLoading ? <PageLoading /> : documents.isError
        ? <ErrorState error={documents.error} onRetry={() => documents.refetch()} />
        : documents.data?.items.length === 0
          ? <EmptyState description="还没有文档，请上传第一份 PDF、DOCX、TXT 或 Markdown" />
          : (
              <Table
                rowKey="documentId"
                columns={columns}
                dataSource={documents.data?.items}
                pagination={{
                  current: page + 1,
                  pageSize: size,
                  total: documents.data?.total,
                  showSizeChanger: true,
                  onChange: (nextPage, nextSize) => { setPage(nextPage - 1); setSize(nextSize) },
                }}
              />
            )}

      <Modal
        open={uploadOpen}
        title="上传新文档"
        okText="开始上传"
        cancelText="取消"
        confirmLoading={upload.isPending}
        onCancel={() => { setUploadOpen(false); form.resetFields() }}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={(values) => upload.mutate(values)}>
          <Form.Item name="name" label="文档名称" rules={[{ required: true, whitespace: true }, { max: 200 }]}>
            <Input placeholder="例如：员工差旅制度" maxLength={200} showCount />
          </Form.Item>
          <Form.Item
            name="file"
            label="原始文件"
            valuePropName="fileList"
            getValueFromEvent={(event) => event?.fileList}
            rules={[{ required: true, message: '请选择文件' }]}
            extra="支持 PDF、DOCX、TXT、Markdown，单文件不超过 50 MiB。"
          >
            <Upload beforeUpload={() => false} maxCount={1} accept={acceptedExtensions}>
              <Button>选择文件</Button>
            </Upload>
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
