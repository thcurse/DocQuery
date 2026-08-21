import { Breadcrumb, Flex, Space, Typography } from 'antd'
import type { ReactNode } from 'react'
import { Link } from 'react-router'

interface Crumb {
  title: string
  to?: string
}

export function PageHeader({
  title,
  description,
  action,
  crumbs = [],
}: {
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
  crumbs?: Crumb[]
}) {
  return (
    <Space orientation="vertical" size={14} className="page-heading">
      {crumbs.length > 0 && (
        <Breadcrumb items={crumbs.map((crumb) => ({
          title: crumb.to ? <Link to={crumb.to}>{crumb.title}</Link> : crumb.title,
        }))} />
      )}
      <Flex justify="space-between" align="flex-start" gap={20} wrap>
        <div>
          <Typography.Title level={2}>{title}</Typography.Title>
          {description && <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>}
        </div>
        {action}
      </Flex>
    </Space>
  )
}
