import { CheckCircleOutlined, CopyOutlined, KeyOutlined } from '@ant-design/icons'
import { Alert, Button, Checkbox, Input, Modal, Space, Typography, message } from 'antd'
import { useEffect, useState } from 'react'

export function SecretModal({ secret, onClose }: { secret: string | null; onClose: () => void }) {
  const [saved, setSaved] = useState(false)

  useEffect(() => setSaved(false), [secret])

  const copy = async () => {
    if (!secret) return
    await navigator.clipboard.writeText(secret)
    message.success('凭证已复制到剪贴板')
  }

  return (
    <Modal
      open={secret !== null}
      title={<Space><KeyOutlined />请立即保存应用凭证</Space>}
      closable={false}
      mask={{ closable: false }}
      keyboard={false}
      footer={(
        <Button type="primary" disabled={!saved} onClick={onClose}>
          <CheckCircleOutlined />我已保存，关闭
        </Button>
      )}
    >
      <Space orientation="vertical" size={18} className="full-width">
        <Alert
          showIcon
          type="warning"
          title="完整凭证只展示这一次"
          description="关闭后无法再次查看。请保存到受控的密钥管理系统，不要粘贴到日志、代码或聊天记录。"
        />
        <Space.Compact block>
          <Input.Password
            aria-label="完整应用凭证"
            value={secret ?? ''}
            readOnly
            visibilityToggle
          />
          <Button icon={<CopyOutlined />} onClick={copy}>复制</Button>
        </Space.Compact>
        <Checkbox checked={saved} onChange={(event) => setSaved(event.target.checked)}>
          我已将凭证安全保存，理解关闭后无法找回
        </Checkbox>
        <Typography.Text type="secondary">
          DocQuery 前端不会把该值写入 URL、浏览器存储或查询缓存。
        </Typography.Text>
      </Space>
    </Modal>
  )
}
