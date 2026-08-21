import { expect, test } from '@playwright/test'

const platformLogin = process.env.DOCQUERY_E2E_PLATFORM_LOGIN ?? 'platform.e2e'
const platformPassword = process.env.DOCQUERY_E2E_PLATFORM_PASSWORD ?? 'Platform Browser Password 2026!'
const tenantLogin = 'tenant.browser.admin'
const tenantPassword = 'Tenant Browser Password 2026!'

const login = async (page: import('@playwright/test').Page, loginName: string, password: string) => {
  await page.getByLabel('管理员账号').fill(loginName)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
}

const logout = async (page: import('@playwright/test').Page, loginName: string) => {
  await page.getByRole('button', { name: new RegExp(loginName) }).click()
  await page.getByText('退出登录', { exact: true }).click()
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
}

test('真实 Spring Boot + MySQL 完成平台与租户核心管理流程', async ({ page }) => {
  const suffix = Date.now().toString(36)
  const tenantName = `浏览器验收租户-${suffix}`
  const applicationName = `售后工单系统-${suffix}`
  const applicationCode = `ticket-${suffix}`
  const knowledgeBaseName = `维修手册-${suffix}`

  await page.goto('/admin/')
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
  await expect(page.getByText('管理员专用入口')).toBeVisible()
  await login(page, platformLogin, platformPassword)

  await expect(page).toHaveURL(/#\/tenants$/)
  await expect(page.getByRole('heading', { name: '租户管理' })).toBeVisible()
  await page.getByRole('button', { name: '创建租户' }).click()
  await page.getByLabel('租户名称').fill(tenantName)
  await page.getByLabel('管理员登录名').fill(tenantLogin)
  await page.getByLabel('初始密码').fill(tenantPassword)
  await page.getByRole('button', { name: '创建租户', exact: true }).last().click()
  await expect(page.getByText(`首个租户管理员 ${tenantLogin} 已创建。`)).toBeVisible()
  await expect(page.getByText(tenantName, { exact: true })).toBeVisible()

  await page.goto('/admin/#/applications')
  await expect(page.getByText('无权访问', { exact: true })).toBeVisible()
  await logout(page, platformLogin)

  await login(page, tenantLogin, tenantPassword)
  await expect(page).toHaveURL(/#\/applications$/)
  await expect(page.getByText(tenantName, { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: '创建应用' }).click()
  await page.getByLabel('应用编码').fill(applicationCode)
  await page.getByLabel('应用名称').fill(applicationName)
  await page.getByLabel('环境').click()
  await page.getByText('生产', { exact: true }).last().click()
  await page.getByLabel('描述').fill('浏览器端到端验收应用')
  await page.getByRole('button', { name: '创建应用', exact: true }).last().click()
  await expect(page.getByText(applicationName, { exact: true })).toBeVisible()

  await page.getByText('知识库管理', { exact: true }).click()
  await page.getByRole('button', { name: '创建知识库' }).click()
  await page.getByLabel('知识库名称').fill(knowledgeBaseName)
  await page.getByLabel('描述').fill('浏览器端到端验收知识库')
  await page.getByRole('button', { name: '创建知识库', exact: true }).last().click()
  await expect(page.getByText(knowledgeBaseName, { exact: true })).toBeVisible()

  await page.getByText('应用管理', { exact: true }).click()
  await page.getByText(applicationName, { exact: true }).click()
  await expect(page.getByRole('heading', { name: applicationName })).toBeVisible()

  await page.getByRole('button', { name: '生成凭证' }).click()
  await page.getByLabel('凭证名称').fill('浏览器验收凭证')
  await page.getByRole('button', { name: '生成凭证', exact: true }).last().click()
  await expect(page.getByText('完整凭证只展示这一次')).toBeVisible()
  const credential = await page.getByLabel('完整应用凭证').inputValue()
  expect(credential).toMatch(/^dq_/)
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 })
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: /我已保存，关闭/ }).click()
  await expect(page.getByText('浏览器验收凭证', { exact: true })).toBeVisible()

  await page.getByRole('tab', { name: /知识库授权/ }).click()
  await page.getByRole('button', { name: '添加授权' }).click()
  const grantDialog = page.getByRole('dialog', { name: '配置知识库授权' })
  await grantDialog.getByRole('combobox', { name: /知识库/ }).click()
  await page.getByText(new RegExp(knowledgeBaseName)).last().click()
  await grantDialog.getByRole('button', { name: '保存授权' }).click()
  await expect(page.getByText(knowledgeBaseName, { exact: true })).toBeVisible()
  await expect(page.getByText('只读', { exact: true })).toBeVisible()

  await page.getByText('知识库管理', { exact: true }).click()
  await page.getByText(knowledgeBaseName, { exact: true }).click()
  await expect(page.getByRole('heading', { name: knowledgeBaseName })).toBeVisible()
  await expect(page.getByText(applicationName, { exact: true })).toBeVisible()
  await expect(page.getByText('只读', { exact: true })).toBeVisible()
})
