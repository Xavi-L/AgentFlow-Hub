import { test, expect, type Page } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { parseJson } from '../src/lib/sequence'

const LAB = '450000000000000001'
const LEGACY = '450000000000000020'
const DISABLED = '450000000000000021'
const docId = (suffix: number) => `450000000000000${suffix}`
test.skip(!process.env.V45_BROWSER, 'Requires the V45 disposable PostgreSQL fixture')

async function login(page: Page, target = '/knowledge-bases') {
  await page.goto(target)
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill('v43-browser')
  await page.getByLabel('密码', { exact: true }).fill('V43-browser-test!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(url => url.pathname === target)
}
async function publicGet(page: Page, endpoint: string) {
  const token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
  const response = await page.request.get(`/api/v1${endpoint}`, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status()).toBe(200)
  return (parseJson(await response.text()) as any).data
}
async function createBase(page: Page, name: string) {
  await page.getByLabel('名称', { exact: true }).fill(name)
  await page.getByLabel('描述（选填）').fill('V45 browser acceptance')
  await page.getByRole('button', { name: '创建知识库 →', exact: true }).click()
  await expect(page).toHaveURL(/\/knowledge-bases\/\d+$/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name)
  return page.url().split('/').at(-1)!
}
async function upload(page: Page, name: string, buffer: Buffer) {
  await page.getByLabel('TXT / MD 单文件').setInputFiles({ name, mimeType: 'text/plain', buffer })
  await page.getByRole('button', { name: '上传文档', exact: true }).click()
  const row = page.locator('[data-document-id]').filter({ hasText: name })
  await expect(row).toContainText('PENDING')
  return row
}

test('real browser creates, uploads TXT and MD, parses actual files and explicitly vectorizes to READY', async ({ page }, info) => {
  const writes: { url: string; body: string | null; bearer: boolean }[] = []
  page.on('request', request => { if (request.method() === 'POST' && request.url().includes('/knowledge-bases')) writes.push({ url: request.url(), body: request.postData(), bearer: !!request.headers().authorization?.startsWith('Bearer ') }) })
  await login(page)
  const kb = await createBase(page, `V45 closed loop ${Date.now()}`)
  expect(JSON.parse(writes[0]!.body!)).toEqual({ name: await page.getByRole('heading', { level: 1 }).textContent(), description: 'V45 browser acceptance' })
  await expect(page.locator('.knowledge-config')).toContainText('dashscope-te-v4-1024-cosine')
  await expect(page.locator('.knowledge-config')).toContainText('structured-token-v1')
  await upload(page, 'policy.txt', Buffer.from('Payment timeout requires checking order and payment logs.'))
  await upload(page, 'refund.md', Buffer.from('# Refund policy\n\nA refund requires a successful payment record.'))
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.locator('[data-document-id] [data-readiness="NOT_READY"]')).toHaveCount(2)
  expect(writes).toHaveLength(3) // Refreshing PENDING never invokes a processing POST.
  let documents = await publicGet(page, `/knowledge-bases/${kb}/documents`)
  expect(documents.items.every((doc: any) => doc.parseStatus === 'PENDING')).toBe(true)
  await page.getByRole('button', { name: '解析待处理文档', exact: true }).click()
  await expect(page.locator('[data-document-id] [data-readiness="INDEXING"]')).toHaveCount(2)
  await expect(page.locator('[data-document-id] .ready-label')).toHaveCount(0)
  const chunks = []
  for (const document of documents.items) {
    const pageOfChunks = await publicGet(page, `/knowledge-bases/${kb}/documents/${document.id}/chunks`)
    expect(pageOfChunks.items.length).toBeGreaterThan(0)
    expect(pageOfChunks.items[0].content).toContain(document.fileName === 'policy.txt' ? 'Payment timeout' : 'Refund policy')
    chunks.push(...pageOfChunks.items)
  }
  await page.getByRole('button', { name: '向量化待处理分块', exact: true }).click()
  await expect(page.locator('[data-document-id] [data-readiness="READY"]')).toHaveCount(2)
  await expect(page.locator('[data-document-id] .ready-label')).toHaveCount(2)
  documents = await publicGet(page, `/knowledge-bases/${kb}/documents`)
  for (const document of documents.items) {
    const detail = await publicGet(page, `/documents/${document.id}`)
    expect(detail).toEqual(document)
    expect(detail.retrievalReadiness).toBe('READY')
    expect(detail.vectorization.completed).toBeGreaterThan(0)
    await page.getByRole('button', { name: `查看 ${document.fileName} 状态详情` }).click()
    await expect(page.getByTestId('document-detail')).toContainText(document.id)
    await expect(page.getByTestId('document-detail').locator('[data-readiness]')).toHaveAttribute('data-readiness', 'READY')
  }
  const upserts = (await readFile(path.join(process.env.V43_CONTROL_DIR!, 'vector-upserts'), 'utf8')).trim().split('\n')
  expect(upserts.length).toBeGreaterThanOrEqual(chunks.length)
  expect(writes.every(write => write.bearer)).toBe(true)
  await page.screenshot({ path: info.outputPath('knowledge-ready.png'), fullPage: true })
  await writeFile(info.outputPath('knowledge-evidence.json'), JSON.stringify({ kb, documents: documents.items, chunks: chunks.length, writes: writes.map(write => ({ url: write.url, bearer: write.bearer })), providers: 'controlled embedding/vector upsert; real JWT/PostgreSQL/upload/storage/parser' }, null, 2))
})

test('pagination, five readiness values, generation counts and incompatible/disabled configuration use real GETs', async ({ page }, info) => {
  await login(page)
  await expect(page.locator('[data-kb-id]')).toHaveCount(20)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('.pagination')).toContainText('第 2 页')
  await expect(page.locator('[data-kb-id]')).not.toHaveCount(0)
  await page.goto(`/knowledge-bases/${LAB}`)
  await expect(page.locator('[data-document-id]')).toHaveCount(20)
  const large = page.locator(`[data-document-id="${docId(202)}"]`)
  await expect(large).toContainText('9007199254740993')
  const indexing = page.locator(`[data-document-id="${docId(105)}"]`)
  await expect(indexing.locator('[data-readiness]')).toHaveAttribute('data-readiness', 'INDEXING')
  await expect(indexing.locator('dd')).toHaveText(['1', '1', '1', '1'])
  const old = page.locator(`[data-document-id="${docId(106)}"]`)
  await expect(old.locator('[data-readiness]')).toHaveAttribute('data-readiness', 'FAILED')
  await expect(old.locator('dd')).toHaveText(['0', '0', '0', '0'])
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('[data-document-id]')).toHaveCount(3)
  for (const [suffix, readiness] of [[100, 'NOT_READY'], [101, 'READY'], [102, 'DEGRADED']] as const) {
    const row = page.locator(`[data-document-id="${docId(suffix)}"]`)
    await expect(row.locator('[data-readiness]')).toHaveAttribute('data-readiness', readiness)
    await expect(row.locator('.ready-label')).toHaveCount(readiness === 'READY' ? 1 : 0)
    await row.getByRole('button').click()
    await expect(page.getByTestId('document-detail').locator('[data-readiness]')).toHaveAttribute('data-readiness', readiness)
  }
  await page.screenshot({ path: info.outputPath('readiness-states.png'), fullPage: true })
  await page.goto(`/knowledge-bases/${LEGACY}`)
  await expect(page.getByRole('alert')).toContainText('配置不兼容')
  await expect(page.locator('[data-document-id] [data-readiness]')).toHaveAttribute('data-readiness', 'FAILED')
  await page.goto(`/knowledge-bases/${DISABLED}`)
  await expect(page.getByRole('alert')).toContainText('知识库已禁用')
  await expect(page.locator('[data-document-id] [data-readiness]')).toHaveAttribute('data-readiness', 'NOT_READY')
})

test('real parsing failure and controlled embedding failure remain unavailable with honest batch counts', async ({ page }) => {
  await login(page)
  await createBase(page, `V45 failures ${Date.now()}`)
  await page.getByLabel('TXT / MD 单文件').setInputFiles({ name: 'bad.pdf', mimeType: 'application/pdf', buffer: Buffer.from('not supported') })
  await expect(page.getByRole('alert')).toContainText('仅支持 TXT / MD')
  await upload(page, 'invalid-utf8.txt', Buffer.from([0xc3, 0x28]))
  await upload(page, 'provider-failure.md', Buffer.from('# Failure\n\nV45_EMBED_FAIL controlled embedding failure.'))
  await page.getByRole('button', { name: '解析待处理文档', exact: true }).click()
  await expect(page.locator('[data-document-id]').filter({ hasText: 'invalid-utf8.txt' }).locator('[data-readiness]')).toHaveAttribute('data-readiness', 'FAILED')
  await expect(page.locator('[data-document-id]').filter({ hasText: 'provider-failure.md' }).locator('[data-readiness]')).toHaveAttribute('data-readiness', 'INDEXING')
  await page.getByRole('button', { name: '向量化待处理分块', exact: true }).click()
  const failed = page.locator('[data-document-id]').filter({ hasText: 'provider-failure.md' })
  await expect(failed.locator('[data-readiness]')).toHaveAttribute('data-readiness', 'FAILED')
  await expect(failed.locator('dd')).toHaveText(['0', '0', '0', '1'])
  await expect(page.locator('[aria-label="入库操作"]')).toContainText('失败 1')
  await expect(page.locator('.ready-label')).toHaveCount(0)
})

test('committed create and upload with lost responses survive reload without automatic resubmission', async ({ page }) => {
  await login(page)
  let creates = 0, kb = ''
  await page.route('**/api/v1/knowledge-bases', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    creates++
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    kb = (await response.json()).data.id
    await route.abort('connectionfailed')
  })
  await page.getByLabel('名称', { exact: true }).fill(`V45 uncertain ${Date.now()}`)
  await page.getByRole('button', { name: '创建知识库 →', exact: true }).click()
  await expect(page.getByTestId('unknown-write')).toContainText('结果待确认')
  await page.reload()
  await expect(page.getByTestId('unknown-write')).toBeVisible()
  await expect(page.getByRole('button', { name: '创建知识库 →', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(page.locator(`[data-kb-id="${kb}"]`)).toBeVisible()
  expect(creates).toBe(1)
  await page.locator(`[data-kb-id="${kb}"]`).click()
  let uploads = 0, document = ''
  await page.route(`**/api/v1/knowledge-bases/${kb}/documents`, async route => {
    if (route.request().method() !== 'POST') return route.continue()
    uploads++
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    document = (await response.json()).data.id
    await route.abort('connectionfailed')
  })
  await page.getByLabel('TXT / MD 单文件').setInputFiles({ name: 'once.md', mimeType: 'text/plain', buffer: Buffer.from('Do not resend this file.') })
  await page.getByRole('button', { name: '上传文档', exact: true }).click()
  await expect(page.getByTestId('unknown-write')).toContainText('结果待确认')
  await page.reload()
  await expect(page.getByTestId('unknown-write')).toBeVisible()
  await expect(page.locator(`[data-document-id="${document}"]`)).toContainText('PENDING')
  expect(uploads).toBe(1)
  expect((await publicGet(page, `/knowledge-bases/${kb}/documents`)).total).toBe(1)
})

test('a real POST response delayed beyond the browser timeout does not fabricate document failure or resend', async ({ page }) => {
  await login(page)
  const kb = await createBase(page, `V45 timeout ${Date.now()}`)
  await upload(page, 'timeout.txt', Buffer.from('Server commits parsing before the transport times out.'))
  let release!: () => void, committed!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const done = new Promise<void>(resolve => { committed = resolve })
  let posts = 0
  await page.route(`**/api/v1/knowledge-bases/${kb}/documents/process-pending`, async route => {
    posts++
    const response = await route.fetch()
    committed()
    await gate
    await route.fulfill({ response }).catch(() => {})
  })
  try {
    await page.getByRole('button', { name: '解析待处理文档', exact: true }).click()
    await done
    await expect(page.getByTestId('unknown-write')).toContainText('结果待确认', { timeout: 25000 })
    await expect(page.locator('[data-document-id] [data-readiness]')).toHaveAttribute('data-readiness', 'NOT_READY')
    await page.getByRole('button', { name: '刷新状态', exact: true }).click()
    await expect(page.locator('[data-document-id] [data-readiness]')).toHaveAttribute('data-readiness', 'INDEXING')
    expect(posts).toBe(1)
  } finally { release() }
})

test('bounded GET polling stops without marking failure; leaving cancels requests and late pages cannot replace a newer page', async ({ page }) => {
  await login(page, `/knowledge-bases/${LAB}`)
  await expect(page.locator('[data-document-id]')).toHaveCount(20)
  await page.clock.install()
  await page.clock.fastForward(65000)
  await expect(page.locator('.poll-message')).toContainText('已达上限')
  await expect(page.locator(`[data-document-id="${docId(105)}"] [data-readiness]`)).toHaveAttribute('data-readiness', 'INDEXING')
  let release!: () => void, fetched!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const captured = new Promise<void>(resolve => { fetched = resolve })
  await page.route(`**/api/v1/knowledge-bases/${LAB}/documents?page=1*`, async route => {
    const response = await route.fetch()
    fetched(); await gate
    await route.fulfill({ response }).catch(() => {})
  })
  try {
    await page.getByRole('button', { name: '刷新状态', exact: true }).click()
    await captured
    await page.getByRole('button', { name: '下一页', exact: true }).click()
    await expect(page.locator('[data-document-id]')).toHaveCount(3)
    release()
    await expect(page.locator('.pagination')).toContainText('第 2 页')
    await expect(page.locator(`[data-document-id="${docId(100)}"]`)).toBeVisible()
    await expect(page.locator(`[data-document-id="${docId(202)}"]`)).toHaveCount(0)
  } finally { release() }
  await page.clock.resume()
})

test('leaving and logout abort pending detail requests; real JWT rejection and missing resources clear protected content', async ({ page }) => {
  await login(page, `/knowledge-bases/${LAB}`)
  await expect(page.locator('[data-document-id]')).toHaveCount(20)
  let release!: () => void, fetched!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const captured = new Promise<void>(resolve => { fetched = resolve })
  const aborted: string[] = []
  page.on('requestfailed', request => aborted.push(request.url()))
  await page.route(`**/api/v1/documents/${docId(202)}`, async route => {
    const response = await route.fetch()
    fetched(); await gate; await route.fulfill({ response }).catch(() => {})
  })
  await page.getByRole('button', { name: '查看 large-generation.txt 状态详情' }).click()
  await captured
  await page.getByRole('link', { name: '任务', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  await expect.poll(() => aborted.some(url => url.endsWith(`/documents/${docId(202)}`))).toBe(true)
  release()
  await expect(page.getByTestId('document-detail')).toHaveCount(0)
  await page.goto(`/knowledge-bases/${LAB}`)
  await expect(page.locator('[data-document-id]')).toHaveCount(20)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.knowledge-writes.v1'))).toBeNull()
  await login(page, '/knowledge-bases/9223372036854775807')
  await expect(page.getByRole('alert')).toContainText('not found')
  await expect(page.getByRole('button', { name: '上传文档', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    const session = JSON.parse(sessionStorage.getItem('agentflow.session.v1')!)
    session.token += '-invalid-signature'
    sessionStorage.setItem('agentflow.session.v1', JSON.stringify(session))
  })
  await page.goto(`/knowledge-bases/${LAB}`)
  await expect(page).toHaveURL(/\/login/)
  await expect(page.locator('[data-document-id]')).toHaveCount(0)
})
