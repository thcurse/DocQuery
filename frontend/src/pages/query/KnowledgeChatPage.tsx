import {
  ClearOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  RobotOutlined,
  SendOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  Collapse,
  Empty,
  Flex,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, api, collectAllPages } from '../../api/client'
import { PageHeader } from '../../components/PageHeader'
import { ErrorState, PageLoading } from '../../components/PageState'
import type { AnswerCitation, AnswerResponse, RetrievalMode, ServiceCallResult } from '../../domain/types'
import { usePageRequest } from '../../hooks/usePageRequest'
import { useTenantId } from '../../hooks/useTenantId'

interface ChatForm {
  applicationId: number
  knowledgeBaseId: number
  credential: string
  query: string
  mode: RetrievalMode
  topK: number
}

interface ChatFailure {
  status: number
  code: string
  message: string
}

interface ChatExchange {
  id: string
  question: string
  knowledgeBaseName: string
  elapsedMs: number
  result?: ServiceCallResult<AnswerResponse>
  failure?: ChatFailure
}

const newKey = () => crypto.randomUUID()

export function buildAnswerRequest(values: Pick<ChatForm, 'query' | 'mode' | 'topK'>) {
  return {
    query: values.query.trim(),
    mode: values.mode,
    topK: values.topK,
  }
}

export function citationLocation(citation: AnswerCitation) {
  const parts = citation.headingPath.length > 0 ? [...citation.headingPath] : ['文档正文']
  if (citation.pageNumber) parts.push(`第 ${citation.pageNumber} 页`)
  return parts.join(' / ')
}

function toFailure(error: unknown): ChatFailure {
  if (error instanceof ApiError) {
    return { status: error.status, code: error.code, message: error.message }
  }
  return { status: 0, code: 'NETWORK_ERROR', message: '无法连接 DocQuery 服务' }
}

export function KnowledgeChatPage() {
  const tenantId = useTenantId()
  const [form] = Form.useForm<ChatForm>()
  const [exchanges, setExchanges] = useState<ChatExchange[]>([])
  const [pendingQuestion, setPendingQuestion] = useState<string>()
  const send = usePageRequest()
  const chatEndRef = useRef<HTMLDivElement>(null)
  const applicationId = Form.useWatch('applicationId', form)

  const applications = useQuery({
    queryKey: ['applications', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listApplications(tenantId, page, size)),
  })
  const knowledgeBases = useQuery({
    queryKey: ['knowledge-bases', tenantId, 'all'],
    queryFn: () => collectAllPages((page, size) => api.listKnowledgeBases(tenantId, page, size)),
  })
  const grants = useQuery({
    queryKey: ['grants', tenantId, 'application', applicationId],
    queryFn: () => collectAllPages((page, size) => api.listGrantsByApplication(tenantId, applicationId, page, size)),
    enabled: Number.isSafeInteger(applicationId) && applicationId > 0,
  })

  const submit = (values: ChatForm) => {
    const question = values.query.trim()
    const startedAt = performance.now()
    const knowledgeBaseName = knowledgeBases.data?.find((item) => item.id === values.knowledgeBaseId)?.name
      ?? `知识库 #${values.knowledgeBaseId}`
    const appendExchange = (outcome: Pick<ChatExchange, 'result' | 'failure'>) => {
      setExchanges((current) => [...current, {
        id: newKey(),
        question,
        knowledgeBaseName,
        elapsedMs: performance.now() - startedAt,
        ...outcome,
      }])
    }
    return send.run(
      (signal) => api.answer(
        values.knowledgeBaseId,
        values.credential.trim(),
        newKey(),
        buildAnswerRequest(values),
        undefined,
        signal,
      ),
      {
        onStart: () => setPendingQuestion(question),
        onSuccess: (result) => {
          appendExchange({ result })
          form.setFieldValue('query', '')
        },
        onError: (error) => appendExchange({ failure: toFailure(error) }),
        onSettled: () => setPendingQuestion(undefined),
      },
    )
  }

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [exchanges, pendingQuestion])

  if (knowledgeBases.isLoading || applications.isLoading) return <PageLoading />
  if (knowledgeBases.isError || applications.isError) {
    const failed = knowledgeBases.isError ? knowledgeBases : applications
    return <ErrorState error={failed.error} onRetry={() => failed.refetch()} />
  }

  const readableKnowledgeBaseIds = new Set((grants.data ?? [])
    .filter((grant) => grant.status === '1' && (grant.permission === '1' || grant.permission === '3'))
    .map((grant) => grant.knowledgeBaseId))

  return (
    <>
      <PageHeader
        title="知识库问答"
        description="用真实知识库完成带引用的单轮问答，适合直接演示已实现的 Answer 能力。"
        action={<Link to="/api-playground"><Button>打开 API 调试</Button></Link>}
      />
      <Alert
        showIcon
        type="info"
        title="当前为单轮问答"
        description="聊天记录只在本页内展示，后续问题不会携带此前对话作为上下文；Credential 也只保存在当前页面内存，刷新或离开页面后即清除。"
        className="page-alert"
      />
      <Form
        form={form}
        clearOnDestroy
        layout="vertical"
        initialValues={{ mode: 'HYBRID', topK: 5 }}
        onValuesChange={(changed) => {
          if ('applicationId' in changed) form.setFieldValue('knowledgeBaseId', undefined)
        }}
        onFinish={submit}
      >
        <Row gutter={20} align="stretch" className="knowledge-chat-layout">
          <Col span={7}>
            <Card title={<Space><SettingOutlined />问答配置</Space>} className="knowledge-chat-settings">
              <Form.Item name="applicationId" label="Application" rules={[{ required: true, message: '请选择应用' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择 Credential 所属应用"
                  options={(applications.data ?? []).map((application) => ({
                    value: application.id,
                    label: `${application.name} · ${application.code}${application.status === '1' ? '' : ' · 已停用'}`,
                    disabled: application.status !== '1',
                  }))}
                />
              </Form.Item>
              <Form.Item name="knowledgeBaseId" label="KnowledgeBase" rules={[{ required: true, message: '请选择知识库' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  loading={grants.isLoading}
                  disabled={!applicationId}
                  placeholder={applicationId ? '选择已获读取授权的知识库' : '请先选择应用'}
                  notFoundContent={applicationId && !grants.isLoading ? '该应用没有可读取的知识库' : undefined}
                  options={(knowledgeBases.data ?? [])
                    .filter((knowledgeBase) => readableKnowledgeBaseIds.has(knowledgeBase.id))
                    .map((knowledgeBase) => ({
                      value: knowledgeBase.id,
                      label: `${knowledgeBase.name} · ID ${knowledgeBase.id}${knowledgeBase.status === '1' ? '' : ' · 已停用'}`,
                      disabled: knowledgeBase.status !== '1',
                    }))}
                />
              </Form.Item>
              {grants.isError && (
                <Alert type="error" showIcon title="读取授权加载失败" className="section-alert" />
              )}
              <Form.Item
                name="credential"
                label="Application Credential"
                rules={[{ required: true, whitespace: true, message: '请输入 Credential' }]}
                extra={<>没有可用 Credential？前往<Link to="/applications">应用管理</Link>创建。</>}
              >
                <Input.Password autoComplete="new-password" placeholder="dq_app_..." visibilityToggle />
              </Form.Item>
              <Row gutter={12}>
                <Col span={15}>
                  <Form.Item name="mode" label="召回模式" rules={[{ required: true }]}>
                    <Select options={[
                      { value: 'HYBRID', label: 'HYBRID · 混合召回' },
                      { value: 'KEYWORD', label: 'KEYWORD · BM25' },
                      { value: 'SEMANTIC', label: 'SEMANTIC · 向量召回' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={9}>
                  <Form.Item name="topK" label="Top-K" rules={[{ required: true }]}>
                    <InputNumber min={1} max={20} className="full-width" />
                  </Form.Item>
                </Col>
              </Row>
              <Alert
                type="warning"
                showIcon
                title="Credential 不会持久化"
                description="不会写入 LocalStorage、SessionStorage、URL 或查询缓存。"
              />
            </Card>
          </Col>
          <Col span={17}>
            <Card
              title={<Space><RobotOutlined />问答窗口</Space>}
              extra={<Button type="text" icon={<ClearOutlined />} disabled={exchanges.length === 0 || send.isPending} onClick={() => setExchanges([])}>清空记录</Button>}
              className="knowledge-chat-card"
            >
              <div className="knowledge-chat-messages" aria-live="polite">
                {exchanges.length === 0 && !pendingQuestion && (
                  <Empty
                    image={<DatabaseOutlined className="knowledge-chat-empty-icon" />}
                    description={(
                      <Space orientation="vertical" size={4}>
                        <Typography.Text strong>选择应用和知识库后开始提问</Typography.Text>
                        <Typography.Text type="secondary">回答将附带文档、章节、页码和证据原文。</Typography.Text>
                      </Space>
                    )}
                  />
                )}
                {exchanges.map((exchange) => <ExchangeView key={exchange.id} exchange={exchange} />)}
                {pendingQuestion && (
                  <>
                    <QuestionBubble question={pendingQuestion} />
                    <div className="chat-message chat-message-assistant">
                      <Avatar icon={<RobotOutlined />} className="chat-avatar-assistant" />
                      <Card size="small" loading className="chat-bubble chat-bubble-assistant" />
                    </div>
                  </>
                )}
                <div ref={chatEndRef} />
              </div>
              <div className="knowledge-chat-composer">
                <Form.Item
                  name="query"
                  rules={[{ required: true, whitespace: true, message: '请输入问题' }, { max: 2000 }]}
                  className="knowledge-chat-query"
                >
                  <Input.TextArea
                    autoSize={{ minRows: 2, maxRows: 5 }}
                    maxLength={2000}
                    showCount
                    placeholder="输入关于当前知识库的问题；Enter 发送，Shift + Enter 换行"
                    disabled={send.isPending}
                    onPressEnter={(event) => {
                      if (!event.shiftKey && !event.nativeEvent.isComposing) {
                        event.preventDefault()
                        form.submit()
                      }
                    }}
                  />
                </Form.Item>
                <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={send.isPending} className="knowledge-chat-send">
                  发送
                </Button>
              </div>
            </Card>
          </Col>
        </Row>
      </Form>
    </>
  )
}

function ExchangeView({ exchange }: { exchange: ChatExchange }) {
  return (
    <>
      <QuestionBubble question={exchange.question} />
      <div className="chat-message chat-message-assistant">
        <Avatar icon={<RobotOutlined />} className="chat-avatar-assistant" />
        <div className="chat-bubble chat-bubble-assistant">
          {exchange.failure
            ? (
                <Alert
                  type="error"
                  showIcon
                  title={`请求失败 · HTTP ${exchange.failure.status || 'NETWORK'}`}
                  description={<Space orientation="vertical" size={2}><span>{exchange.failure.message}</span><Typography.Text code>{exchange.failure.code}</Typography.Text></Space>}
                />
              )
            : <AnswerView result={exchange.result!} />}
          <Flex gap={8} wrap className="chat-message-meta">
            <Tag>{exchange.knowledgeBaseName}</Tag>
            <Typography.Text type="secondary">耗时 {(exchange.elapsedMs / 1000).toFixed(2)} 秒</Typography.Text>
            {exchange.result?.requestId && <Typography.Text type="secondary">Request ID: <Typography.Text code>{exchange.result.requestId}</Typography.Text></Typography.Text>}
            {exchange.result?.data.queryExecutionId && <Typography.Text type="secondary">Execution ID: <Typography.Text code>{exchange.result.data.queryExecutionId}</Typography.Text></Typography.Text>}
          </Flex>
        </div>
      </div>
    </>
  )
}

function QuestionBubble({ question }: { question: string }) {
  return (
    <div className="chat-message chat-message-user">
      <div className="chat-bubble chat-bubble-user">{question}</div>
      <Avatar icon={<UserOutlined />} className="chat-avatar-user" />
    </div>
  )
}

function AnswerView({ result }: { result: ServiceCallResult<AnswerResponse> }) {
  const { data } = result
  const answered = Boolean(data.answer)
  return (
    <Space orientation="vertical" size={12} className="full-width">
      <Flex gap={8} wrap>
        <Tag color={answered ? 'success' : 'warning'}>{answered ? '已回答' : '证据不足'}</Tag>
        <Tag color="blue">{data.executedMode}</Tag>
        {data.degraded && <Tag color="warning">已降级 · {data.degradationReason ?? '未说明原因'}</Tag>}
      </Flex>
      <Typography.Paragraph className="knowledge-chat-answer">
        {data.answer ?? '当前检索证据不足，无法可靠回答这个问题。'}
      </Typography.Paragraph>
      {data.citations.length > 0 && (
        <Collapse
          size="small"
          items={[{
            key: 'citations',
            label: `查看引用证据（${data.citations.length}）`,
            children: (
              <Space orientation="vertical" size={10} className="full-width">
                {data.citations.map((citation) => (
                  <Card
                    key={citation.citationIndex}
                    size="small"
                    title={<Space><FileTextOutlined /><span>[{citation.citationIndex}] {citation.documentName}</span></Space>}
                  >
                    <Typography.Paragraph type="secondary" className="knowledge-chat-citation-location">
                      {citationLocation(citation)}
                    </Typography.Paragraph>
                    <blockquote className="evidence-block">{citation.text}{citation.truncated ? '…' : ''}</blockquote>
                  </Card>
                ))}
              </Space>
            ),
          }]}
        />
      )}
    </Space>
  )
}
