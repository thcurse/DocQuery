import { DatabaseOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Form, Input, Space, Typography } from 'antd'
import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { errorMessage } from '../components/PageState'
import { useAuth } from '../auth/AuthContext'

export function LoginPage() {
  const { admin, login } = useAuth()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()
  const location = useLocation()

  if (admin) return <Navigate to={admin.role === '1' ? '/tenants' : '/applications'} replace />

  const submit = async (values: { loginName: string; password: string }) => {
    setSubmitting(true)
    setError(null)
    try {
      await login(values.loginName, values.password)
      const from = (location.state as { from?: string } | null)?.from
      navigate(from && from !== '/login' ? from : '/', { replace: true })
    } catch (caught) {
      setError(errorMessage(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-brand-panel">
        <div className="brand-mark"><DatabaseOutlined /></div>
        <Typography.Title>DocQuery</Typography.Title>
        <Typography.Paragraph>
          为业务系统提供可靠的知识库管理、检索证据与受控问答能力。
        </Typography.Paragraph>
        <div className="login-boundary-note">
          <Typography.Text strong>管理员专用入口</Typography.Text>
          <Typography.Text>业务系统应通过 Application Credential 调用服务接口，不能在此登录。</Typography.Text>
        </div>
      </section>
      <section className="login-form-panel">
        <div className="login-card">
          <Space orientation="vertical" size={6} className="login-title">
            <Typography.Title level={2}>登录管理后台</Typography.Title>
            <Typography.Text type="secondary">使用平台管理员或租户管理员账号</Typography.Text>
          </Space>
          {error && <Alert showIcon type="error" title="登录失败" description={error} />}
          <Form layout="vertical" size="large" requiredMark={false} onFinish={submit}>
            <Form.Item name="loginName" label="管理员账号" rules={[{ required: true, message: '请输入管理员账号' }]}>
              <Input autoComplete="username" prefix={<UserOutlined />} placeholder="请输入账号" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password autoComplete="current-password" prefix={<LockOutlined />} placeholder="请输入密码" />
            </Form.Item>
            <Button block type="primary" htmlType="submit" loading={submitting}>登录</Button>
          </Form>
          <Typography.Paragraph type="secondary" className="login-help">
            没有注册入口。首个平台管理员由部署人员通过一次性交互命令创建。
          </Typography.Paragraph>
        </div>
      </section>
    </main>
  )
}
