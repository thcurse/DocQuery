import { Alert, Button, Empty, Result, Skeleton, Space, Typography } from 'antd'
import { ApiError } from '../api/client'

export function PageLoading({ rows = 5 }: { rows?: number }) {
  return <Skeleton active paragraph={{ rows }} />
}

export function EmptyState({ description }: { description: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={description} />
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (error instanceof ApiError && error.status === 403) {
    return (
      <Result
        status="403"
        title="无权访问"
        subTitle="当前管理员角色或租户范围不允许访问此资源。"
      />
    )
  }
  const code = error instanceof ApiError ? error.code : 'NETWORK_ERROR'
  const message = error instanceof ApiError ? error.message : '服务暂时不可用，请检查网络后重试。'
  return (
    <Alert
      showIcon
      type="error"
      title="加载失败"
      description={(
        <Space orientation="vertical">
          <Typography.Text>{message}</Typography.Text>
          <Typography.Text type="secondary">错误码：{code}</Typography.Text>
          {onRetry && <Button onClick={onRetry}>重试</Button>}
        </Space>
      )}
    />
  )
}

export const errorMessage = (error: unknown) => {
  if (error instanceof ApiError) return `${error.message}（${error.code}）`
  return '服务暂时不可用，请稍后重试'
}
