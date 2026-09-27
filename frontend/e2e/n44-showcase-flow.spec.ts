import path from 'node:path'
import { expect, test } from '@playwright/test'

const platformLogin = process.env.DOCQUERY_E2E_PLATFORM_LOGIN ?? 'platform.e2e'
const platformPassword =
  process.env.DOCQUERY_E2E_PLATFORM_PASSWORD ?? 'Platform Browser Password 2026!'
const tenantLogin = 'tenant.n44.admin'
const tenantPassword = 'Tenant N44 Browser Password 2026!'
const backupTenantLogin = 'tenant.n44.backup'
const backupTenantPassword = 'Tenant N44 Backup Password 2026!'
const resetBackupPassword = 'Tenant N44 Reset Password 2026!'

const login = async (
  page: import('@playwright/test').Page,
  loginName: string,
  password: string,
) => {
  await page.getByLabel('管理员账号').fill(loginName)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
}

const logout = async (page: import('@playwright/test').Page, loginName: string) => {
  await page.getByRole('button', { name: new RegExp(loginName) }).click()
  await page.getByText('退出登录', { exact: true }).click()
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
}

test('N4.4 真实依赖完成上传到 READY、Retrieve/Answer、审计、说明和删除', async ({
  page,
  request,
}) => {
  test.setTimeout(300_000)
  page.setDefaultTimeout(15_000)
  const suffix = Date.now().toString(36)
  const tenantName = `N4.4 演示租户-${suffix}`
  const applicationName = `差旅业务系统-${suffix}`
  const applicationCode = `travel-${suffix}`
  const knowledgeBaseName = `员工制度库-${suffix}`
  const documentName = `员工差旅制度-${suffix}`
  const fixture = path.resolve('e2e/fixtures/showcase-policy.md')

  await page.goto('/admin/')
  await login(page, platformLogin, platformPassword)
  await expect(page).toHaveURL(/#\/tenants$/)

  await page.goto('/admin/#/api-playground')
  await expect(page.getByText('无权访问', { exact: true }).last()).toBeVisible()
  await page.getByRole('navigation').getByText('租户管理', { exact: true }).click()
  await page.getByRole('button', { name: '创建租户' }).first().click()
  await page.getByLabel('租户名称').fill(tenantName)
  await page.getByLabel('管理员登录名').fill(tenantLogin)
  await page.getByLabel('初始密码').fill(tenantPassword)
  await page
    .getByRole('button', { name: /创建租户/ })
    .last()
    .click()
  await expect(page.getByText(tenantName, { exact: true })).toBeVisible()

  await page.getByRole('navigation').getByText('使用说明', { exact: true }).click()
  await expect(page.getByRole('heading', { name: '使用说明' })).toBeVisible()
  await expect(page.getByText('平台管理员先完成租户开通', { exact: true })).toBeVisible()
  await page.getByRole('navigation').getByText('租户管理', { exact: true }).click()
  await page.getByText(tenantName, { exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: tenantLogin })).toBeVisible()
  await page.getByRole('button', { name: '新增管理员' }).click()
  const administratorDialog = page.getByRole('dialog', { name: '新增租户管理员' })
  await administratorDialog.getByLabel('管理员登录名').fill(backupTenantLogin)
  await administratorDialog.getByLabel('初始密码').fill(backupTenantPassword)
  await administratorDialog.getByRole('button', { name: '创建管理员' }).click()
  const backupAdministratorRow = page.locator('tr').filter({ hasText: backupTenantLogin })
  await expect(backupAdministratorRow).toContainText('已启用')
  await backupAdministratorRow.getByRole('button', { name: '重置密码' }).click()
  const passwordDialog = page.getByRole('dialog', {
    name: new RegExp(`重置密码：${backupTenantLogin}`),
  })
  await passwordDialog.getByLabel('新密码').fill(resetBackupPassword)
  await passwordDialog.getByRole('button', { name: '设置新密码' }).click()
  await backupAdministratorRow.getByRole('button', { name: /停用$/ }).click()
  await page.getByRole('button', { name: /确认停用$/ }).click()
  await expect(backupAdministratorRow).toContainText('已停用')
  const primaryAdministratorRow = page.locator('tr').filter({ hasText: tenantLogin })
  await expect(primaryAdministratorRow.getByRole('button', { name: /停用$/ })).toBeDisabled()
  await logout(page, platformLogin)

  await login(page, tenantLogin, tenantPassword)
  await page.getByRole('navigation').getByText('应用管理', { exact: true }).click()
  await page.getByRole('button', { name: '创建应用' }).first().click()
  await page.getByLabel('应用编码').fill(applicationCode)
  await page.getByLabel('应用名称').fill(applicationName)
  await page.getByLabel('环境').selectOption('TESTING')
  await page
    .getByRole('button', { name: /创建应用/ })
    .last()
    .click()
  await expect(page.getByText(applicationName, { exact: true })).toBeVisible()

  await page.getByRole('navigation').getByText('知识库管理', { exact: true }).click()
  await page.getByRole('button', { name: '创建知识库' }).first().click()
  await page.getByLabel('知识库名称').fill(knowledgeBaseName)
  await page.getByLabel('描述').fill('N4.4 浏览器真实链路验收知识库')
  await page
    .getByRole('button', { name: /创建知识库/ })
    .last()
    .click()
  await page.getByRole('navigation').getByText('应用管理', { exact: true }).click()
  await page.getByText(applicationName, { exact: true }).click()

  await page.getByRole('button', { name: '生成凭证' }).click()
  await page.getByLabel('凭证名称').fill('N4.4 浏览器验收凭证')
  await page.getByRole('button', { name: '生成凭证', exact: true }).last().click()
  const credential = (await page.getByTestId('created-secret').innerText()).trim()
  expect(credential).toMatch(/^dq_app_/)
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '完成', exact: true }).click()

  await page.getByRole('tab', { name: /知识库授权/ }).click()
  await page.getByRole('button', { name: '添加授权' }).click()
  const grantDialog = page.getByRole('dialog', { name: '配置知识库授权' })
  await grantDialog.getByLabel('知识库', { exact: true }).selectOption({
    label: await grantDialog.locator('option').filter({ hasText: knowledgeBaseName }).innerText(),
  })
  await grantDialog.getByRole('button', { name: '保存授权' }).click()
  await expect(page.getByText('只读', { exact: true })).toBeVisible()

  await page.getByRole('navigation').getByText('知识库管理', { exact: true }).click()
  await page.getByText(knowledgeBaseName, { exact: true }).click()
  await page.getByRole('button', { name: '上传文档' }).first().click()
  const uploadDialog = page.getByRole('dialog', { name: '上传新文档' })
  await uploadDialog.getByLabel('文档名称').fill(documentName)
  await uploadDialog.locator('input[type="file"]').setInputFiles(fixture)
  await uploadDialog.getByRole('button', { name: '开始上传' }).click()
  await expect(page.getByText(documentName, { exact: true })).toBeVisible()
  await expect(
    page
      .getByRole('row')
      .filter({ hasText: documentName })
      .getByText('已就绪', { exact: true })
      .first(),
  ).toBeVisible({ timeout: 150_000 })

  await page.getByRole('navigation').getByText('API 调试', { exact: true }).click()
  await page
    .getByLabel('Application', { exact: true })
    .selectOption({ label: `${applicationName} · ${applicationCode}` })
  await expect(page.getByLabel('KnowledgeBase', { exact: true })).toBeEnabled()
  await page.getByLabel('KnowledgeBase', { exact: true }).selectOption({ label: knowledgeBaseName })
  await page.getByLabel('Application Credential').fill(credential)
  await page.getByLabel('问题').fill('差旅住宿报销标准是什么？')
  expect(
    await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })),
  ).toEqual({ local: 0, session: 0 })
  await page.getByRole('button', { name: '发送真实请求' }).click()
  await expect(
    page.getByText('普通员工出差住宿上限为每晚 500 元，超出部分需自行承担。').first(),
  ).toBeVisible({ timeout: 60_000 })
  await page.getByText('响应详情', { exact: true }).click()
  const requestId = (await page.getByTestId('request-id').innerText()).trim()
  expect(requestId).toMatch(/[0-9a-f-]{36}/i)

  await page.getByLabel('接口', { exact: true }).selectOption('answer')
  await page.getByRole('button', { name: '发送真实请求' }).click()
  await expect(page.getByText('回答状态：ANSWERED')).toBeVisible({ timeout: 60_000 })
  await expect(
    page.locator('.answer-text').filter({ hasText: '普通员工出差住宿上限为每晚 500 元' }),
  ).toBeVisible()
  await page.getByRole('button', { name: /查看 .* 条引用/ }).click()
  await expect(page.getByText(documentName, { exact: true }).last()).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByLabel('响应方式').selectOption({ label: '流式回答' })
  await page.getByRole('button', { name: '发送真实请求' }).click()
  await expect(page.getByText('正在生成回答，内容尚未完成校验…')).toBeVisible()
  await expect(page.getByText('回答状态：ANSWERED')).toHaveCount(1, { timeout: 60_000 })
  await expect(page.getByRole('button', { name: /查看 .* 条引用/ })).toHaveCount(1)

  await page.getByRole('navigation').getByText('查询审计', { exact: true }).click()
  await page.getByPlaceholder('Request ID').fill(requestId)
  await page.getByRole('button', { name: '筛选' }).click()
  const auditRow = page.locator('tr').filter({ hasText: requestId }).first()
  await expect(auditRow).toContainText('RETRIEVE')
  await auditRow.getByText('查看详情').click()
  await expect(page.getByText('隐私边界', { exact: true })).toBeVisible()
  await expect(page.getByText('差旅住宿报销标准是什么？', { exact: true })).toHaveCount(0)

  await page.getByRole('navigation').getByText('使用说明', { exact: true }).click()
  await expect(page.getByRole('heading', { name: '使用说明' })).toBeVisible()
  await expect(page.getByText('五步完成接入', { exact: true })).toBeVisible()
  const openApi = await request.get('/admin/docs/docquery-service-api.openapi.yaml')
  expect(openApi.ok()).toBeTruthy()
  expect(await openApi.text()).toContain('/{knowledgeBaseId}/retrieve:')
  const postman = await request.get('/admin/docs/docquery-service-api.postman_collection.json')
  expect(postman.ok()).toBeTruthy()
  expect((await postman.json()).item).toHaveLength(3)

  await page.getByRole('navigation').getByText('知识库管理', { exact: true }).click()
  await page.getByText(knowledgeBaseName, { exact: true }).click()
  await page.getByText(documentName, { exact: true }).click()
  await page.getByRole('button', { name: '删除文档' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('已删除', { exact: true }).first()).toBeVisible({ timeout: 120_000 })
})
