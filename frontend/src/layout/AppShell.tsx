import {
  AppstoreOutlined,
  AuditOutlined,
  BankOutlined,
  BookOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Avatar, Button, Dropdown, Flex, Layout, Menu, Space, Tag, Typography } from 'antd'
import type { MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { roleLabel } from '../domain/display'

const { Header, Sider, Content } = Layout

export function AppShell() {
  const { admin, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  const tenant = useQuery({
    queryKey: ['tenant', admin?.tenantId],
    queryFn: () => api.getTenant(admin!.tenantId!),
    enabled: admin?.role === '2' && admin.tenantId !== null,
  })

  if (!admin) return null

  const items: MenuProps['items'] = admin.role === '1'
    ? [
        { key: '/tenants', icon: <BankOutlined />, label: '租户管理' },
        { key: '/guide', icon: <BookOutlined />, label: '使用说明' },
      ]
    : [
        { key: '/applications', icon: <AppstoreOutlined />, label: '应用管理' },
        { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库管理' },
        { key: '/api-playground', icon: <ExperimentOutlined />, label: 'API 调试' },
        { key: '/query-audits', icon: <AuditOutlined />, label: '查询审计' },
        { key: '/guide', icon: <BookOutlined />, label: '使用说明' },
      ]

  const selected = ['/knowledge-bases', '/applications', '/api-playground', '/query-audits', '/guide', '/tenants']
    .find((path) => location.pathname.startsWith(path)) ?? '/tenants'

  const userMenu: MenuProps['items'] = [
    {
      key: 'identity',
      disabled: true,
      label: (
        <Space orientation="vertical" size={0}>
          <Typography.Text strong>{admin.loginName}</Typography.Text>
          <Typography.Text type="secondary">{roleLabel(admin.role)}</Typography.Text>
        </Space>
      ),
    },
    { type: 'divider' },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
  ]

  return (
    <Layout className="app-layout">
      <Sider width={236} className="app-sider">
        <Flex align="center" gap={12} className="app-brand">
          <div className="app-brand-icon"><DatabaseOutlined /></div>
          <div>
            <Typography.Text className="app-brand-name">DocQuery</Typography.Text>
            <Typography.Text className="app-brand-subtitle">知识服务管理后台</Typography.Text>
          </div>
        </Flex>
        <Menu
          mode="inline"
          theme="dark"
          selectedKeys={[selected]}
          items={items}
          onClick={({ key }) => navigate(key)}
        />
        <div className="scope-card">
          <SafetyCertificateOutlined />
          <Space orientation="vertical" size={0}>
            <span>当前管理范围</span>
            <strong>{admin.role === '1' ? '全平台租户' : tenant.data?.name ?? `租户 #${admin.tenantId}`}</strong>
          </Space>
        </div>
      </Sider>
      <Layout>
        <Header className="app-header">
          <Flex justify="space-between" align="center">
            <Space>
              <Typography.Text type="secondary">当前上下文</Typography.Text>
              <Tag color="blue">
                {admin.role === '1' ? '平台' : tenant.data?.name ?? `租户 #${admin.tenantId}`}
              </Tag>
            </Space>
            <Dropdown
              menu={{
                items: userMenu,
                onClick: async ({ key }) => {
                  if (key === 'logout') {
                    await logout()
                    navigate('/login', { replace: true })
                  }
                },
              }}
              placement="bottomRight"
            >
              <Button type="text" className="identity-button">
                <Avatar size="small" icon={<UserOutlined />} />
                <span>{admin.loginName}</span>
              </Button>
            </Dropdown>
          </Flex>
        </Header>
        <Content className="app-content"><Outlet /></Content>
      </Layout>
    </Layout>
  )
}
