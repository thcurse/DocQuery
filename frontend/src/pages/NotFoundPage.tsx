import { Button, Result } from 'antd'
import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <Result
      status="404"
      title="页面不存在"
      subTitle="请检查地址，或返回当前角色的管理首页。"
      extra={<Link to="/"><Button type="primary">返回首页</Button></Link>}
    />
  )
}
