import { describe, expect, it } from 'vitest'
import {
  documentStatusLabel,
  environmentLabel,
  jobStatusLabel,
  permissionLabel,
  roleLabel,
  sourceFormatLabel,
  statusLabel,
  versionStatusLabel,
} from './display'

describe('管理后台稳定编码文案', () => {
  it('展示中文生命周期状态', () => {
    expect(statusLabel('1')).toBe('已启用')
    expect(statusLabel('2')).toBe('已停用')
    expect(statusLabel('3')).toBe('已撤销')
  })

  it('展示角色、环境和授权语义', () => {
    expect(roleLabel('1')).toBe('平台管理员')
    expect(roleLabel('2')).toBe('租户管理员')
    expect(environmentLabel('PRODUCTION')).toBe('生产')
    expect(permissionLabel('1')).toBe('只读')
  })

  it('不混用文档、版本和任务状态', () => {
    expect(documentStatusLabel('2')).toBe('删除中')
    expect(versionStatusLabel('2')).toBe('已就绪')
    expect(jobStatusLabel('2')).toBe('执行中')
    expect(sourceFormatLabel('4')).toBe('Markdown')
  })
})
