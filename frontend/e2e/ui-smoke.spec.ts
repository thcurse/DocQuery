import { expect, test, type Page } from '@playwright/test'
const streamResponse = () => ({
  contentType: 'text/event-stream',
  body:
    'event: start\ndata: ' +
    JSON.stringify({ version: 1, requestId: 'mock-request' }) +
    '\n\n' +
    'event: delta\ndata: ' +
    JSON.stringify({ seq: 1, text: '正在生成的文字' }) +
    '\n\n' +
    'event: done\ndata: ' +
    JSON.stringify({ result: answer, replayed: false }) +
    '\n\n',
})
const timestamp = '2026-09-26T08:00:00Z'
const kb = {
  id: 2,
  tenantId: 1,
  name: '团队知识库',
  description: '产品手册、业务规范与团队经验，让每一个答案都有据可循。',
  status: '1',
  createdAt: timestamp,
  updatedAt: timestamp,
}
const application = {
  id: 3,
  tenantId: 1,
  name: '业务助手',
  code: 'business-helper',
  environment: 'TESTING',
  description: '内部应用',
  status: '1',
  createdAt: timestamp,
  updatedAt: timestamp,
}
const job = {
  processingJobId: 5001,
  attemptNo: 1,
  status: '3',
  jobType: 'DOCUMENT_PROCESSING',
  versionNo: 1,
  failureRetryable: false,
  failureCode: null,
  failureMessage: null,
  finishedAt: timestamp,
  createdAt: timestamp,
}
const version = (n: number) => ({
  documentVersionId: 1000 + n,
  versionNo: n,
  status: '2',
  active: n === 125,
  originalFilename: `revision-${n}.txt`,
  sourceFormat: 'TXT',
  sourceSizeBytes: 1024,
  readyAt: timestamp,
  failedAt: null,
  failureRetryable: false,
  latestProcessingJob: { ...job, processingJobId: 5000 + n },
})
const documentFixture = {
  documentId: 4,
  knowledgeBaseId: 2,
  name: '员工差旅制度',
  documentStatus: '1',
  activeVersion: version(125),
  latestVersion: version(125),
  latestDeletionJob: null,
  createdAt: timestamp,
  updatedAt: timestamp,
}
const citation = {
  citationIndex: 1,
  documentId: 4,
  documentVersionId: 1125,
  versionNo: 125,
  documentName: '员工差旅制度',
  headingPath: ['报销制度', '住宿标准'],
  text: '员工出差住宿上限为每晚 500 元。',
  blockId: 'b1',
  pageNumber: 8,
  sourcePosition: null,
  truncated: false,
}
const answer = {
  queryExecutionId: 'execution-1',
  knowledgeBaseId: 2,
  status: 'ANSWERED',
  answer: '住宿上限为每晚 500 元。[1]',
  requestedMode: 'HYBRID',
  executedMode: 'HYBRID',
  degraded: false,
  degradationReason: null,
  citations: [citation],
}
const audit = {
  id: 1,
  requestId: 'request-1',
  applicationId: 3,
  knowledgeBaseId: 2,
  credentialFingerprint: 'fingerprint',
  operation: 'ANSWER',
  outcome: 'SUCCEEDED',
  httpStatus: 200,
  durationMs: 100,
  startedAt: timestamp,
  queryExecutionId: 'execution-1',
  requestedMode: 'HYBRID',
  executedMode: 'HYBRID',
}
async function mockApi(page: Page, role = '2') {
  await page.route(/\/api\/(?:admin|v1)\//, async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname
    let body: unknown = null
    if (p.endsWith('/auth/me') || p.endsWith('/auth/login'))
      body = { id: 1, loginName: 'demo.admin', role, tenantId: role === '2' ? 1 : null }
    else if (p.endsWith('/auth/csrf')) body = { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }
    else if (p.endsWith('/answer/stream')) {
      await route.fulfill(streamResponse())
      return
    } else if (p.endsWith('/answer')) body = answer
    else if (p.endsWith('/retrieve'))
      body = {
        ...answer,
        results: [
          {
            rank: 1,
            documentId: 4,
            documentVersionId: 1125,
            versionNo: 125,
            documentName: documentFixture.name,
            headingPath: ['住宿标准'],
            channels: ['KEYWORD'],
            evidence: [{ ...citation, keywordHighlights: [] }],
          },
        ],
      }
    else if (p.endsWith('/query-audits/1')) body = audit
    else if (p.endsWith('/query-audits')) body = { items: [audit], total: 1 }
    else if (/\/processing-jobs\/\d+$/.test(p)) body = { job, attemptHistory: [job] }
    else if (p.endsWith('/versions')) {
      const index = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      body = {
        items: Array.from({ length: 125 }, (_, i) => version(125 - i)).slice(
          index * size,
          (index + 1) * size,
        ),
        total: 125,
        page: index,
        size,
      }
    } else if (p.endsWith('/documents/4')) body = documentFixture
    else if (p.endsWith('/documents')) body = { items: [documentFixture], total: 1 }
    else if (p.endsWith('/grants'))
      body = {
        items: [
          {
            id: 1,
            applicationId: 3,
            knowledgeBaseId: 2,
            permission: '1',
            status: '1',
            grantedAt: timestamp,
          },
        ],
        total: 1,
      }
    else if (p.endsWith('/credentials'))
      body =
        route.request().method() === 'POST'
          ? { credential: 'dq_app_test_secret' }
          : {
              items: [
                {
                  id: 1,
                  name: '测试凭证',
                  keyIdPrefix: 'dq_test',
                  status: '1',
                  createdAt: timestamp,
                  lastUsedAt: null,
                },
              ],
              total: 1,
            }
    else if (p.endsWith('/knowledge-bases/2')) body = kb
    else if (p.endsWith('/knowledge-bases'))
      body = {
        items: [
          kb,
          {
            ...kb,
            id: 5,
            name: '产品使用指南',
            description: '帮助团队快速了解产品功能与最佳实践。',
          },
          { ...kb, id: 6, name: '项目技术文档', description: '架构设计、接口约定与工程规范。' },
        ],
        total: 3,
      }
    else if (p.endsWith('/applications/3')) body = application
    else if (p.endsWith('/applications')) body = { items: [application], total: 1 }
    else if (p.endsWith('/administrators'))
      body = {
        items: [{ id: 1, loginName: 'tenant.admin', status: '1', createdAt: timestamp }],
        total: 1,
      }
    else if (p.endsWith('/tenants/1'))
      body = { id: 1, name: '示例团队', status: '1', createdAt: timestamp, updatedAt: timestamp }
    else if (p.endsWith('/tenants'))
      body = {
        items: [
          { id: 1, name: '示例团队', status: '1', createdAt: timestamp, updatedAt: timestamp },
        ],
        total: 1,
      }
    else {
      await route.fulfill({ status: 404, json: { message: `Unexpected test API: ${p}` } })
      return
    }
    await route.fulfill({ json: body, headers: { 'X-DocQuery-Request-Id': 'request-1' } })
  })
}
for (const width of [1440, 1024, 390]) {
  test(`界面 ${width}px：列表、详情、问答、审计及无横向溢出（模拟 API）`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    await mockApi(page)
    const errors: string[] = []
    page.on('pageerror', (e) => {
      errors.push(e.message)
      console.log(e.stack)
    })
    for (const [path, title] of [
      ['knowledge-bases', '知识库管理'],
      ['knowledge-bases/2', '团队知识库'],
      ['applications', '应用管理'],
      ['applications/3', '业务助手'],
      ['knowledge-bases/2/documents/4', '员工差旅制度'],
      ['knowledge-chat', '知识库问答'],
      ['query-audits', '查询审计'],
      ['query-audits/1', 'ANSWER 查询审计'],
      ['guide', '使用说明'],
    ]) {
      await page.goto(`/admin/#/${path}`)
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
      await expect
        .poll(() =>
          page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
        )
        .toBe(true)
      if (['knowledge-bases', 'knowledge-chat'].includes(path))
        await page.screenshot({ path: `test-results/visual-${path}-${width}.png`, fullPage: true })
    }
    expect(errors).toEqual([])
  })
}
test('一次性凭证需确认；问答只提交当前问题且离页清除凭证（模拟 API）', async ({ page }) => {
  await mockApi(page)
  await page.goto('/admin/#/applications/3')
  await page.getByRole('button', { name: '生成凭证', exact: true }).click()
  await page.getByLabel('凭证名称').fill('测试')
  await page
    .getByRole('dialog', { name: '生成凭证' })
    .getByRole('button', { name: '生成凭证' })
    .click()
  await expect(page.getByTestId('created-secret')).toContainText('dq_app_test_secret')
  await expect(page.getByRole('button', { name: '完成', exact: true })).toBeDisabled()
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: '完成', exact: true }).click()
  await page.getByRole('tab', { name: '知识库授权' }).click()
  await page.getByRole('button', { name: '添加授权', exact: true }).click()
  await page.getByLabel('知识库', { exact: true }).selectOption('2')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page.goto('/admin/#/knowledge-chat')
  await page.getByLabel('Application', { exact: true }).selectOption('3')
  await expect(page.getByLabel('KnowledgeBase', { exact: true })).toBeEnabled()
  await page.getByLabel('KnowledgeBase', { exact: true }).selectOption('2')
  await page.getByLabel('Application Credential').fill('dq_app_test_secret')
  await page.getByLabel('问题', { exact: true }).fill('住宿标准？')
  const sent = page.waitForRequest((r) => r.url().endsWith('/answer/stream'))
  await page.getByRole('button', { name: '发送问题' }).click()
  expect((await sent).postDataJSON()).toEqual({ query: '住宿标准？', mode: 'HYBRID', topK: 5 })
  await expect(page.getByText('住宿上限为每晚 500 元。[1]', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看 1 条引用' }).click()
  await expect(page.getByText('第 8 页', { exact: false }).last()).toBeVisible()
  await page.keyboard.press('Escape')
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
  await page.getByRole('navigation').getByText('知识库管理', { exact: true }).click()
  await page.getByRole('navigation').getByText('知识库问答', { exact: true }).click()
  await expect(page.getByLabel('Application Credential')).toHaveValue('')
  await expect(page.getByText('住宿上限为每晚 500 元。[1]', { exact: true })).toHaveCount(0)
})

test('上传与重建后回到第一页，旧历史页在激活后刷新（模拟 API）', async ({ page }) => {
  await mockApi(page)
  let count = 125
  let processing = false
  let versionReads = 0
  await page.route(
    /\/api\/admin\/v1\/tenants\/1\/knowledge-bases\/2\/documents\/4(?:\/versions|\/rebuild)?(?:\?.*)?$/,
    async (route) => {
      const url = new URL(route.request().url())
      if (route.request().method() === 'POST') {
        count++
        processing = true
        await route.fulfill({
          status: 202,
          json: {
            documentId: 4,
            documentVersionId: 1000 + count,
            versionNo: count,
            processingJobId: 5000 + count,
          },
        })
      } else if (url.pathname.endsWith('/versions')) {
        versionReads++
        const index = Number(url.searchParams.get('page'))
        const size = Number(url.searchParams.get('size'))
        const items = Array.from({ length: count }, (_, i) => ({
          ...version(count - i),
          status: processing && i === 0 ? '1' : '2',
          active: processing ? i === 1 : i === 0,
        })).slice(index * size, (index + 1) * size)
        await route.fulfill({ json: { items, total: count, page: index, size } })
      } else
        await route.fulfill({
          json: {
            ...documentFixture,
            activeVersion: version(processing ? count - 1 : count),
            latestVersion: { ...version(count), status: processing ? '1' : '2' },
          },
        })
    },
  )
  await page.goto('/admin/#/knowledge-bases/2/documents/4')
  await expect(page.getByText('revision-125.txt')).toBeVisible()
  await page.locator('.n-pagination-item').filter({ hasText: /^7$/ }).click()
  await expect(page.getByText('revision-1.txt')).toBeVisible()
  await page.getByRole('button', { name: '上传新版本', exact: true }).click()
  await page
    .getByLabel('原始文件')
    .setInputFiles({ name: 'new.txt', mimeType: 'text/plain', buffer: Buffer.from('new version') })
  await page.getByRole('button', { name: '开始上传', exact: true }).click()
  await expect(page.getByText('revision-126.txt')).toBeVisible()
  await expect(page.getByRole('button', { name: '重建', exact: true })).toBeDisabled()
  processing = false
  await expect(page.locator('tr').filter({ hasText: 'revision-126.txt' })).toContainText(
    '当前生效',
    { timeout: 10000 },
  )
  await page.locator('.n-pagination-item').filter({ hasText: /^7$/ }).click()
  await page.getByRole('button', { name: '重建', exact: true }).click()
  await page.getByRole('button', { name: '确认重建', exact: true }).click()
  await expect(page.getByText('revision-127.txt')).toBeVisible()
  await page.locator('.n-pagination-item').filter({ hasText: /^7$/ }).click()
  await expect(page.getByText('revision-1.txt')).toBeVisible()
  const previousReads = versionReads
  processing = false
  await expect(page.getByRole('button', { name: '重建', exact: true })).toBeEnabled({
    timeout: 10000,
  })
  await expect.poll(() => versionReads).toBeGreaterThan(previousReads)
  await expect(page.getByText('revision-1.txt')).toBeVisible()
})

test('失败保留问题且可重试，离页取消待处理问答（模拟 API）', async ({ page }) => {
  await mockApi(page)
  await page.goto('/admin/#/knowledge-chat')
  await page.getByLabel('Application', { exact: true }).selectOption('3')
  await page.getByLabel('KnowledgeBase', { exact: true }).selectOption('2')
  await page.getByLabel('Application Credential').fill('dq_app_test_secret')
  await page.getByLabel('问题', { exact: true }).fill('住宿标准？')
  let requests = 0
  const keys: string[] = []
  await page.route('**/api/v1/service/knowledge-bases/2/answer/stream', async (route) => {
    requests++
    keys.push(route.request().headers()['idempotency-key'])
    if (requests === 1)
      await route.fulfill({
        status: 503,
        json: { code: 'ANSWER_MODEL_UNAVAILABLE', message: '模型暂不可用' },
      })
    else await route.fulfill(streamResponse())
  })
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('请求失败', { exact: true })).toBeVisible()
  await expect(page.getByLabel('问题', { exact: true })).toHaveValue('住宿标准？')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('住宿上限为每晚 500 元。[1]', { exact: true })).toBeVisible()
  expect(keys[0]).toBe(keys[1])
  let release!: () => void
  const waiting = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/api/v1/service/knowledge-bases/2/answer/stream', async (route) => {
    await waiting
    await route.fulfill(streamResponse()).catch(() => {})
  })
  await page.getByLabel('问题', { exact: true }).fill('另一个问题')
  const started = page.waitForRequest((r) => r.url().endsWith('/answer/stream'))
  await page.getByRole('button', { name: '发送问题' }).click()
  await started
  const cancelled = page.waitForEvent('requestfailed', (r) => r.url().endsWith('/answer/stream'))
  await page.getByRole('navigation').getByText('知识库管理', { exact: true }).click()
  await cancelled
  release()
  await page.getByRole('navigation').getByText('知识库问答', { exact: true }).click()
  await expect(page.getByLabel('Application Credential')).toHaveValue('')
  await expect(page.getByText('住宿上限为每晚 500 元。[1]', { exact: true })).toHaveCount(0)
})
test('文档版本超过 100 条可分页查询任务，切换文档重置页面（模拟 API）', async ({ page }) => {
  await mockApi(page)
  await page.goto('/admin/#/knowledge-bases/2/documents/4')
  await expect(page.getByText('revision-125.txt', { exact: false })).toBeVisible()
  await page.locator('.n-pagination-item').filter({ hasText: /^7$/ }).click()
  await expect(page.getByText('revision-1.txt', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: /#5001/ }).click()
  await expect(page.getByText('DOCUMENT_PROCESSING', { exact: true })).toBeVisible()
  await page.goto('/admin/#/knowledge-bases/2')
  await page.getByRole('link', { name: '员工差旅制度', exact: true }).click()
  await expect(page.getByText('revision-125.txt', { exact: false })).toBeVisible()
  await expect(page.getByText('处理任务 #5001', { exact: true })).toHaveCount(0)
})
test('平台权限、空状态、错误重试、登录窄屏与静态下载（模拟 API）', async ({ page, request }) => {
  await mockApi(page, '1')
  await page.goto('/admin/')
  await expect(page).toHaveURL(/#\/tenants$/)
  await page.goto('/admin/#/applications')
  await expect(page.getByText('无权访问', { exact: true }).last()).toBeVisible()
  await page.goto('/admin/#/tenants/1')
  await expect(page.getByRole('button', { name: '停用', exact: true })).toBeDisabled()
  await page.route('**/api/admin/v1/tenants?*', (r) =>
    r.fulfill({ status: 503, json: { code: 'TEST_ERROR', message: '暂不可用' } }),
  )
  await page.goto('/admin/#/tenants')
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText('加载失败', { exact: true })).toBeVisible()
  await page.route('**/api/admin/v1/tenants?*', (r) => r.fulfill({ json: { items: [], total: 0 } }))
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByText('暂无数据', { exact: true })).toBeVisible()
  await page.goto('/admin/#/login')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('heading', { name: '登录管理后台' })).toBeVisible()
  await page.screenshot({ path: 'test-results/visual-login-390.png', fullPage: true })
  expect((await request.get('/admin/docs/docquery-service-api.openapi.yaml')).ok()).toBe(true)
  expect(
    (await (await request.get('/admin/docs/docquery-service-api.postman_collection.json')).json())
      .item,
  ).toHaveLength(3)
})

test('真实分块时先显示预览、完成后才展示引用，停止保留未完成内容（模拟流）', async ({ page }) => {
  await mockApi(page)
  await page.addInitScript(
    ({ finalAnswer }) => {
      const original = window.fetch.bind(window)
      window.fetch = async (input, init) => {
        if (!String(input).endsWith('/answer/stream')) return original(input, init)
        const encoder = new TextEncoder()
        let timers: ReturnType<typeof setTimeout>[] = []
        return new Response(
          new ReadableStream({
            start(controller) {
              const send = (event: string, data: unknown) =>
                controller.enqueue(
                  encoder.encode('event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'),
                )
              send('start', { version: 1, requestId: 'stream-test' })
              send('progress', { stage: 'generating' })
              timers = [
                setTimeout(() => send('delta', { seq: 1, text: '这是一段尚未完成的回答' }), 100),
                setTimeout(() => {
                  send('done', { result: finalAnswer, replayed: false })
                  controller.close()
                }, 2000),
              ]
              init?.signal?.addEventListener(
                'abort',
                () => {
                  timers.forEach(clearTimeout)
                  controller.error(new DOMException('Aborted', 'AbortError'))
                },
                { once: true },
              )
            },
            cancel() {
              timers.forEach(clearTimeout)
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        )
      }
    },
    { finalAnswer: answer },
  )
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/admin/#/knowledge-chat')
  await page.getByLabel('Application', { exact: true }).selectOption('3')
  await page.getByLabel('KnowledgeBase', { exact: true }).selectOption('2')
  await page.getByLabel('Application Credential').fill('test-secret')
  await page.getByLabel('问题', { exact: true }).fill('第一个问题')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('这是一段尚未完成的回答', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /查看.*条引用/ })).toHaveCount(0)
  await expect(page.getByText(answer.answer, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /查看.*条引用/ }).click()
  await expect(page.getByText('引用来源', { exact: true }).last()).toBeVisible()
  await page.keyboard.press('Escape')
  await page.getByLabel('问题', { exact: true }).fill('第二个问题')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('这是一段尚未完成的回答', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '停止生成' }).click()
  await expect(page.getByText('未完成，未经最终校验', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '发送问题' })).toBeEnabled()
  await page.waitForTimeout(2100)
  await expect(page.getByText('这是一段尚未完成的回答', { exact: true })).toBeVisible()
})

test('校验错误清除预览；重试保留快照和幂等键，重新发起与重新回答使用新键', async ({ page }) => {
  await mockApi(page)
  await page.goto('/admin/#/knowledge-chat')
  await page.getByLabel('Application', { exact: true }).selectOption('3')
  await page.getByLabel('KnowledgeBase', { exact: true }).selectOption('2')
  await page.getByLabel('Application Credential').fill('dq_app_old')
  await page.getByLabel('问题', { exact: true }).fill('原问题')
  const sent: { key: string; query: string; authorization: string }[] = []
  await page.route('**/api/v1/service/knowledge-bases/2/answer/stream', async (route) => {
    const request = route.request()
    sent.push({
      key: request.headers()['idempotency-key'],
      query: request.postDataJSON().query,
      authorization: request.headers().authorization,
    })
    if (sent.length === 1)
      await route.fulfill({
        contentType: 'text/event-stream',
        body:
          'event: start\ndata: {"version":1,"requestId":"invalid"}\n\n' +
          'event: delta\ndata: {"seq":1,"text":"必须清除的无效答案"}\n\n' +
          'event: error\ndata: {"code":"ANSWER_OUTPUT_INVALID","message":"答案引用校验失败，请重试","retryable":true,"clearPreview":true}\n\n',
      })
    else if (sent.length === 2)
      await route.fulfill({
        status: 409,
        json: { code: 'REQUEST_IN_PROGRESS', message: '请求仍在执行，请稍后重试' },
      })
    else if (sent.length === 3)
      await route.fulfill({
        status: 409,
        json: { code: 'IDEMPOTENCY_CONFLICT', message: '请求配置已变化' },
      })
    else await route.fulfill(streamResponse())
  })
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('答案引用校验失败，请重试').first()).toBeVisible()
  await expect(page.getByText('必须清除的无效答案')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /查看.*引用/ })).toHaveCount(0)
  await page.getByLabel('问题', { exact: true }).fill('新问题草稿')
  await page.getByLabel('Application Credential').fill('dq_app_current')
  await page.getByRole('button', { name: '重试回答', exact: true }).click()
  await expect(page.getByText('请求仍在执行，请稍后重试').first()).toBeVisible()
  expect(sent).toHaveLength(2)
  expect(sent[1]).toEqual({ ...sent[0], authorization: 'Bearer dq_app_current' })
  await page.getByRole('button', { name: '重试回答', exact: true }).click()
  await expect(page.getByRole('button', { name: '重新发起', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重新发起', exact: true }).click()
  await expect(page.getByText('回答状态：已回答', { exact: true })).toBeVisible()
  expect(sent[3].key).not.toBe(sent[0].key)
  expect(sent[3].query).toBe('原问题')
  await expect(page.getByLabel('问题', { exact: true })).toHaveValue('新问题草稿')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '重新回答', exact: true })).toHaveCount(2)
  expect(sent[4].query).toBe('新问题草稿')
  expect(sent[4].key).not.toBe(sent[0].key)
  await page.getByRole('button', { name: '重新回答', exact: true }).first().click()
  await expect.poll(() => sent.length).toBe(6)
  expect(sent[5].query).toBe('原问题')
  expect(new Set(sent.map((x) => x.key)).size).toBe(4)
})

test('模型配置不兼容不提供原样重试；资料不足替换临时预览', async ({ page }) => {
  await mockApi(page)
  await page.goto('/admin/#/knowledge-chat')
  await page.getByLabel('Application', { exact: true }).selectOption('3')
  await page.getByLabel('KnowledgeBase', { exact: true }).selectOption('2')
  await page.getByLabel('Application Credential').fill('dq_app_current')
  await page.getByLabel('问题', { exact: true }).fill('配置验证')
  await page.route('**/api/v1/service/knowledge-bases/2/answer/stream', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: 'event: start\ndata: {"version":1,"requestId":"config"}\n\nevent: error\ndata: {"code":"ANSWER_MODEL_REQUEST_INVALID","message":"模型请求配置不兼容，请检查模型配置","retryable":false,"clearPreview":true}\n\n',
    }),
  )
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('模型请求配置不兼容，请检查模型配置').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '重试回答', exact: true })).toHaveCount(0)
  await page.route('**/api/v1/service/knowledge-bases/2/answer/stream', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body:
        'event: start\ndata: {"version":1,"requestId":"insufficient"}\n\nevent: delta\ndata: {"seq":1,"text":"应被最终结果替换"}\n\nevent: done\ndata: ' +
        JSON.stringify({
          result: { ...answer, status: 'INSUFFICIENT_EVIDENCE', answer: null, citations: [] },
          replayed: false,
        }) +
        '\n\n',
    }),
  )
  await page.getByLabel('问题', { exact: true }).fill('确实缺少资料')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('回答状态：资料不足', { exact: true })).toBeVisible()
  await expect(page.getByText('应被最终结果替换')).toHaveCount(0)
})
