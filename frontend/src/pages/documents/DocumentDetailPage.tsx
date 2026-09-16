import { DeleteOutlined, FileAddOutlined, RedoOutlined } from '@ant-design/icons'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd'
import type { UploadFile } from 'antd'
import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { api } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { DocumentStatusTag, JobStatusTag, VersionStatusTag } from '../../components/WorkflowStatusTag'
import { ErrorState, PageLoading, errorMessage } from '../../components/PageState'
import { formatBytes, formatDateTime, sourceFormatLabel } from '../../domain/display'
import type { DocumentVersion, ProcessingJobSummary } from '../../domain/types'
import { useTenantId } from '../../hooks/useTenantId'
import { queryClient } from '../../queryClient'

interface VersionUploadForm { file: UploadFile[] }
const acceptedExtensions = '.pdf,.docx,.txt,.md,.markdown'
const maxFileBytes = 50 * 1024 * 1024

export function DocumentDetailPage() {
  const tenantId = useTenantId()
  const knowledgeBaseId = Number(useParams().knowledgeBaseId)
  const documentId = Number(useParams().documentId)

  return <DocumentDetail key={`${tenantId}:${knowledgeBaseId}:${documentId}`} tenantId={tenantId} knowledgeBaseId={knowledgeBaseId} documentId={documentId} />
}

function DocumentDetail({ tenantId, knowledgeBaseId, documentId }: {
  tenantId: number
  knowledgeBaseId: number
  documentId: number
}) {
  const [uploadOpen, setUploadOpen] = useState(false)
  const [selectedJobId, setSelectedJobId] = useState<number>()
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20 })
  const [form] = Form.useForm<VersionUploadForm>()

  const validIds = Number.isSafeInteger(knowledgeBaseId) && knowledgeBaseId > 0
    && Number.isSafeInteger(documentId) && documentId > 0
  const document = useQuery({
    queryKey: ['document', tenantId, knowledgeBaseId, documentId],
    queryFn: () => api.getDocument(tenantId, knowledgeBaseId, documentId),
    enabled: validIds,
    refetchInterval: (query) => {
      const data = query.state.data
      return data && (data.documentStatus === '2' || data.latestVersion?.status === '1') ? 3000 : false
    },
  })
  const versions = useQuery({
    queryKey: ['document-versions', tenantId, knowledgeBaseId, documentId, pagination.current, pagination.pageSize],
    queryFn: () => api.listDocumentVersions(tenantId, knowledgeBaseId, documentId, pagination.current - 1, pagination.pageSize),
    enabled: document.isSuccess,
    placeholderData: keepPreviousData,
    refetchInterval: (query) => document.data?.latestVersion?.status === '1'
      || query.state.data?.items.some((version) => version.status === '1') ? 3000 : false,
  })
  useEffect(() => {
    // The latest processing version can be outside the current history page.
    // Refresh that page when processing finishes or the active version changes.
    void queryClient.invalidateQueries({ queryKey: ['document-versions', tenantId, knowledgeBaseId, documentId] })
  }, [tenantId, knowledgeBaseId, documentId, document.data?.latestVersion?.documentVersionId,
    document.data?.latestVersion?.status, document.data?.activeVersion?.documentVersionId, document.data?.documentStatus])
  const job = useQuery({
    queryKey: ['processing-job', tenantId, selectedJobId],
    queryFn: () => api.getProcessingJob(tenantId, selectedJobId!),
    enabled: selectedJobId !== undefined,
  })

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['document', tenantId, knowledgeBaseId, documentId] }),
      queryClient.invalidateQueries({ queryKey: ['document-versions', tenantId, knowledgeBaseId, documentId] }),
      queryClient.invalidateQueries({ queryKey: ['documents', tenantId, knowledgeBaseId] }),
    ])
  }

  const uploadVersion = useMutation({
    mutationFn: async (values: VersionUploadForm) => {
      const file = values.file[0]?.originFileObj
      if (!file) throw new Error('请选择文件')
      if (file.size > maxFileBytes) throw new Error('文件不能超过 50 MiB')
      return api.uploadDocumentVersion(tenantId, knowledgeBaseId, documentId, file, crypto.randomUUID())
    },
    onSuccess: async (accepted) => {
      setUploadOpen(false)
      form.resetFields()
      message.success(`新版本 v${accepted.versionNo} 已受理`)
      setPagination((previous) => ({ ...previous, current: 1 }))
      await refresh()
    },
    onError: (error) => message.error(error instanceof Error ? error.message : errorMessage(error)),
  })
  const retryProcessing = useMutation({
    mutationFn: (processingJobId: number) => api.retryProcessingJob(tenantId, processingJobId, crypto.randomUUID()),
    onSuccess: async (accepted) => { message.success(`重试任务 #${accepted.processingJobId} 已创建`); await refresh() },
    onError: (error) => message.error(errorMessage(error)),
  })
  const rebuildDocument = useMutation({
    mutationFn: () => api.rebuildDocument(
      tenantId, knowledgeBaseId, documentId, crypto.randomUUID(),
    ),
    onSuccess: async (accepted) => {
      message.success(`重建版本 v${accepted.versionNo} 已受理`)
      setPagination((previous) => ({ ...previous, current: 1 }))
      await refresh()
    },
    onError: (error) => message.error(errorMessage(error)),
  })
  const deleteDocument = useMutation({
    mutationFn: () => api.deleteDocument(tenantId, knowledgeBaseId, documentId, crypto.randomUUID()),
    onSuccess: async () => { message.success('删除请求已受理'); await refresh() },
    onError: (error) => message.error(errorMessage(error)),
  })
  const retryDeletion = useMutation({
    mutationFn: () => api.retryDocumentDeletion(tenantId, knowledgeBaseId, documentId, crypto.randomUUID()),
    onSuccess: async () => { message.success('删除重试已受理'); await refresh() },
    onError: (error) => message.error(errorMessage(error)),
  })

  if (!validIds) return <ErrorState error={new Error('Invalid document id')} />
  if (document.isLoading) return <PageLoading />
  if (document.isError) return <ErrorState error={document.error} onRetry={() => document.refetch()} />
  if (!document.data) return null
  const data = document.data
  const deletionFailed = data.latestDeletionJob?.status === '4'
  const canRebuild = data.documentStatus === '1'
    && data.activeVersion?.status === '2'
    && data.latestVersion?.status !== '1'

  const columns = [
    {
      title: '版本',
      render: (_: unknown, version: DocumentVersion) => (
        <Space><Typography.Text strong>v{version.versionNo}</Typography.Text>{version.active && <Tag color="blue">当前生效</Tag>}</Space>
      ),
    },
    { title: '状态', dataIndex: 'status', render: (status: DocumentVersion['status']) => <VersionStatusTag status={status} /> },
    { title: '文件', render: (_: unknown, version: DocumentVersion) => `${version.originalFilename} · ${sourceFormatLabel(version.sourceFormat)} · ${formatBytes(version.sourceSizeBytes)}` },
    {
      title: '处理任务',
      render: (_: unknown, version: DocumentVersion) => version.latestProcessingJob
        ? <Button type="link" onClick={() => setSelectedJobId(version.latestProcessingJob!.processingJobId)}>#{version.latestProcessingJob.processingJobId} · <JobStatusTag status={version.latestProcessingJob.status} /></Button>
        : '—',
    },
    { title: '就绪/失败时间', render: (_: unknown, version: DocumentVersion) => formatDateTime(version.readyAt ?? version.failedAt) },
    {
      title: '操作',
      render: (_: unknown, version: DocumentVersion) => version.status === '3' && version.failureRetryable && version.latestProcessingJob
        ? <Button type="link" icon={<RedoOutlined />} loading={retryProcessing.isPending} onClick={() => retryProcessing.mutate(version.latestProcessingJob!.processingJobId)}>重试处理</Button>
        : null,
    },
  ]

  return (
    <>
      <PageHeader
        title={data.name}
        description={`Document ID：${data.documentId}`}
        crumbs={[
          { title: '知识库管理', to: '/knowledge-bases' },
          { title: `KnowledgeBase #${knowledgeBaseId}`, to: `/knowledge-bases/${knowledgeBaseId}` },
          { title: data.name },
        ]}
        action={data.documentStatus !== '3' && (
          <Space>
            <Button icon={<FileAddOutlined />} disabled={data.documentStatus !== '1'} onClick={() => setUploadOpen(true)}>上传新版本</Button>
            <Popconfirm
              title="确认重建该文档？"
              description="系统将复用原始文件，重新解析并生成检索数据，可能产生供应商费用。重建期间当前版本继续可用，成功后自动切换。"
              okText="确认重建"
              cancelText="取消"
              onConfirm={() => rebuildDocument.mutate()}
            >
              <Button
                icon={<RedoOutlined />}
                disabled={!canRebuild}
                loading={rebuildDocument.isPending}
              >重建</Button>
            </Popconfirm>
            {deletionFailed ? (
              <Button danger icon={<RedoOutlined />} loading={retryDeletion.isPending} onClick={() => retryDeletion.mutate()}>重试删除</Button>
            ) : (
              <Popconfirm
                title="删除这份文档？"
                description="删除会异步清理原文、派生对象和搜索投影，完成后不可恢复。"
                okText="确认删除"
                cancelText="取消"
                onConfirm={() => deleteDocument.mutate()}
              >
                <Button danger icon={<DeleteOutlined />} disabled={data.documentStatus !== '1'} loading={deleteDocument.isPending}>删除文档</Button>
              </Popconfirm>
            )}
          </Space>
        )}
      />

      {data.documentStatus === '2' && <Alert type="warning" showIcon title="文档正在删除" description="删除完成前不可上传新版本。" className="page-alert" />}
      {deletionFailed && <Alert type="error" showIcon title="文档删除失败" description={`${data.latestDeletionJob?.failureMessage ?? data.latestDeletionJob?.failureCode ?? '请重试删除'}`} className="page-alert" />}
      <Card className="detail-summary-card">
        <Descriptions column={3}>
          <Descriptions.Item label="文档状态"><DocumentStatusTag status={data.documentStatus} /></Descriptions.Item>
          <Descriptions.Item label="当前生效版本">{data.activeVersion ? `v${data.activeVersion.versionNo}` : '暂无'}</Descriptions.Item>
          <Descriptions.Item label="最新版本">{data.latestVersion ? `v${data.latestVersion.versionNo}` : '暂无'}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatDateTime(data.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{formatDateTime(data.updatedAt)}</Descriptions.Item>
          <Descriptions.Item label="删除时间">{formatDateTime(data.deletedAt)}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="版本与处理记录">
        {versions.isLoading ? <PageLoading /> : versions.isError
          ? <ErrorState error={versions.error} onRetry={() => versions.refetch()} />
          : <Table
              rowKey="documentVersionId"
              columns={columns}
              dataSource={versions.data?.items}
              loading={versions.isFetching}
              pagination={{
                ...pagination,
                total: versions.data?.total ?? 0,
                showSizeChanger: true,
                pageSizeOptions: [10, 20, 50, 100],
                showTotal: (total) => `共 ${total} 个版本`,
                onChange: (current, pageSize) => setPagination((previous) => ({
                  current: pageSize === previous.pageSize ? current : 1,
                  pageSize,
                })),
              }}
            />}
      </Card>

      <Modal
        open={uploadOpen}
        title="上传新版本"
        okText="开始上传"
        cancelText="取消"
        confirmLoading={uploadVersion.isPending}
        onCancel={() => { setUploadOpen(false); form.resetFields() }}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Alert type="info" showIcon title="READY 后自动切换" description="新版本处理期间，查询继续使用当前 activeVersion；处理失败也不会影响旧版本。" className="modal-alert" />
        <Form form={form} layout="vertical" onFinish={(values) => uploadVersion.mutate(values)}>
          <Form.Item
            name="file"
            label="原始文件"
            valuePropName="fileList"
            getValueFromEvent={(event) => event?.fileList}
            rules={[{ required: true, message: '请选择文件' }]}
            extra="支持 PDF、DOCX、TXT、Markdown，单文件不超过 50 MiB。"
          >
            <Upload beforeUpload={() => false} maxCount={1} accept={acceptedExtensions}><Button>选择文件</Button></Upload>
          </Form.Item>
        </Form>
      </Modal>

      <Drawer open={selectedJobId !== undefined} title={`处理任务 #${selectedJobId}`} width={620} onClose={() => setSelectedJobId(undefined)}>
        {job.isLoading ? <PageLoading /> : job.isError
          ? <ErrorState error={job.error} onRetry={() => job.refetch()} />
          : job.data && (
              <>
                <Descriptions column={2} size="small" bordered>
                  <Descriptions.Item label="状态"><JobStatusTag status={job.data.job.status} /></Descriptions.Item>
                  <Descriptions.Item label="任务类型">{job.data.job.jobType}</Descriptions.Item>
                  <Descriptions.Item label="版本">v{job.data.job.versionNo}</Descriptions.Item>
                  <Descriptions.Item label="当前尝试">#{job.data.job.attemptNo}</Descriptions.Item>
                  <Descriptions.Item label="失败码">{job.data.job.failureCode ?? '—'}</Descriptions.Item>
                  <Descriptions.Item label="是否可重试">{job.data.job.failureRetryable ? '是' : '否'}</Descriptions.Item>
                  <Descriptions.Item label="失败信息" span={2}>{job.data.job.failureMessage ?? '—'}</Descriptions.Item>
                </Descriptions>
                <Typography.Title level={5}>尝试历史</Typography.Title>
                <Table
                  rowKey="processingJobId"
                  size="small"
                  pagination={false}
                  dataSource={job.data.attemptHistory}
                  columns={[
                    { title: '任务 ID', dataIndex: 'processingJobId' },
                    { title: '尝试', dataIndex: 'attemptNo' },
                    { title: '状态', dataIndex: 'status', render: (status: ProcessingJobSummary['status']) => <JobStatusTag status={status} /> },
                    { title: '结束时间', dataIndex: 'finishedAt', render: formatDateTime },
                  ]}
                />
              </>
            )}
      </Drawer>
    </>
  )
}
