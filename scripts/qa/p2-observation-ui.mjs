import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { performance } from 'node:perf_hooks'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261030/p2-fixes-r1')
const dist = path.join(root, 'dist'), stamp = '2026-10-29T00:00:00Z'
const result = { headless: true, isolatedHttpFixtures: true, realBackendAcceptance: false, cases: [], cleanup: false }
const server = http.createServer(async (req, res) => {
  try {
    let file = path.resolve(dist, '.' + decodeURIComponent(new URL(req.url, 'http://qa.invalid').pathname))
    if (!file.startsWith(dist + path.sep) || !path.extname(file)) file = path.join(dist, 'index.html')
    const body = await fs.readFile(file)
    res.writeHead(200, { 'Content-Type': { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' }[path.extname(file)] || 'application/octet-stream' }); res.end(body)
  } catch { res.writeHead(404); res.end() }
})
await fs.mkdir(out, { recursive: true }); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
let browser
async function run(id, title, routePath, options, operation) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } }), hits = [], errors = []
  let reset = false, copyAttempts = 0, reportVersion = 0
  await context.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname, method = request.method(); hits.push({ path: p, method })
    let status = 200, data
    if (p.endsWith('/copy-preview')) data = { sourceDigest: 'a'.repeat(64), itemCount: 1, limit: 200 }
    else if (p.endsWith('/copy') && method === 'POST') {
      hits[hits.length - 1].payload = request.postDataJSON()
      if (++copyAttempts === 1) { await route.abort('connectionreset'); return }
      data = { rootCatalogId: 'copied', catalogCount: 1, definitionCount: 0 }
    }
    else if (p.endsWith('/schedules/s1') && method === 'PUT') {
      hits[hits.length - 1].payload = request.postDataJSON()
      if (reportVersion === 0) { status = 409; data = null; reportVersion = 1 } else data = { version: 2 }
    }
    else if (p.endsWith('/schedules')) data = [{ id: 's1', name: reportVersion ? '其他成员的新计划' : '原始计划', projectIds: ['p'], version: reportVersion, cronExpression: '0 0 0 1 1 ? 2099', timezone: 'UTC', periodDays: 7, enabled: false, lastRunAt: null }]
    else if (p.includes('/reports/quality?')) data = { from: '2026-10-01', to: '2026-10-30', projectCount: 0, summary: { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }, projects: [], trend: [] }
    else if (p.endsWith('/catalogs')) data = [{ id: 'root', name: '源目录', parentId: null, sortOrder: 0, createdAt: stamp, updatedAt: stamp }]
    else if (p.endsWith('/definitions')) data = [{ id: 'a', catalogId: 'root', method: 'GET', path: '/a', name: '接口A', currentVersionId: 'v', versionCount: 1, status: 'DRAFT', createdAt: stamp, updatedAt: stamp }]
    else if (p.endsWith('/versions')) data = [{ id: 'v', versionName: '1.0.0', requestHeaders: '[]', requestQuery: '[]', requestPath: '[]', requestBody: '', responseBody: '{}', exampleResponse: '{"statusCode":201}', status: 'DRAFT', createdAt: stamp }]
    else if (p.endsWith('/workspace-preferences')) data = { revision: 0, state: { folders: [], hiddenIds: [], folderIds: {}, displayMode: 'NAME' } }
    else if (p.endsWith('/auth/me')) { status = reset && options.self ? 401 : 200; data = { id: 'me', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp } }
    else if (p.endsWith('/auth/csrf')) data = { headerName: 'X-CSRF', token: 'fixture' }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA组织', code: 'QA' }], projects: { o: [{ id: 'p', name: 'QA项目', code: 'P', status: 'ACTIVE', description: '', createdAt: stamp, updatedAt: stamp }] } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else if (p.endsWith('/accounts')) data = { items: [{ id: options.self ? 'me' : 'other', account: 'qa-account', email: 'qa@example.test', displayName: 'QA', enabled: true, createdAt: stamp, updatedAt: stamp }], total: 1, page: 1, pageSize: 10 }
    else if (p.endsWith('/password') && method === 'PUT') { status = options.failure ? 503 : 204; if (!options.failure) reset = true }
    else if (p.endsWith('/environments')) data = [{ id: 'env', name: '测试', environmentCode: 'test', enabled: true, defaultEnvironment: true }, { id: 'off', name: '停用环境', environmentCode: 'off', enabled: false, defaultEnvironment: false }]
    else if (p.endsWith('/parameters')) data = { environments: ['test', 'orphan'], parameters: [{ id: 'legacy', environmentCode: 'orphan', key: 'LEGACY', value: 'preserved', updatedAt: stamp }] }
    else if (p.endsWith('/secrets')) data = { environments: ['test'], secrets: [] }
    else if (p.endsWith('/api-debug/history')) data = options.history ? [options.history] : []
    else if (p.endsWith('/api-design/preferences')) data = { revision: 0, state: { folders: [], hiddenIds: [], folderIds: {}, displayMode: 'NAME' } }
    else data = []
    await route.fulfill(status === 204 ? { status } : { status, json: status >= 400 ? { code: 'DEPENDENCY_UNAVAILABLE', message: 'QA受控失败' } : { data } })
  })
  const page = await context.newPage(); page.setDefaultTimeout(8_000); page.on('pageerror', e => errors.push(e.message))
  const started = performance.now()
  try {
    await page.goto(origin + routePath); await operation(page, hits); assert.deepEqual(errors, [])
    result.cases.push({ id, title, status: 'PASS', durationMs: Math.round(performance.now() - started) })
  } catch (error) { result.cases.push({ id, title, status: 'FAIL', error: error.message, browserErrors: errors, pageText: (await page.locator('body').innerText()).slice(0, 2500), requests: hits }) }
  finally { await context.close() }
}
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('TC-P2-D01-06', '孤立参数可见且编辑禁用', '/project-settings/environments', {}, async page => {
    const row = page.locator('tr').filter({ hasText: 'LEGACY' }); await row.waitFor(); assert.ok((await row.innerText()).includes('缺失定义'))
    assert.equal(await row.getByRole('button', { name: '编辑', exact: true }).isDisabled(), true)
  })
  await run('TC-P2-D03-CFG-02', '停用环境仍可维护并明确不可执行', '/project-settings/environments', {}, async page => {
    await page.getByRole('button', { name: '新建参数', exact: true }).click()
    const modal = page.locator('.arco-modal').filter({ hasText: '新建环境参数' }); await modal.waitFor()
    assert.ok((await modal.innerText()).includes('不可执行'))
    await modal.locator('.arco-select-view').click()
    const option = page.locator('.arco-select-option:visible').filter({ hasText: '停用环境' }); await option.waitFor()
    assert.ok(!(await option.getAttribute('class')).includes('disabled')); await option.click()
  })
  await run('TC-P2-D10-13', '目录复制断线后重试保持同一Key', '/api-testing/interfaces', {}, async (page, hits) => {
    for (let n = 0; n < 2; n++) {
      await page.locator('.catalog-entry').hover(); await page.locator('[aria-label="目录 源目录 更多操作"]').click()
      await page.locator('.arco-dropdown-option:visible').filter({ hasText: /复制/ }).first().click()
      await page.locator('.arco-message').filter({ hasText: n === 0 ? '相同请求重试' : '完整复制' }).waitFor()
    }
    const copies = hits.filter(hit => hit.path.endsWith('/copy'))
    assert.equal(copies.length, 2); assert.deepEqual(copies[0].payload, copies[1].payload)
    assert.equal(hits.filter(hit => hit.path.endsWith('/catalogs') && hit.method === 'POST').length, 0)
  })
  await run('TC-P2-D22-07/08', '冲突保留草稿，主动读取版本后单独保存', '/reports', {}, async (page, hits) => {
    await page.getByRole('button', { name: '编辑', exact: true }).click()
    const modal = page.locator('.arco-modal').filter({ hasText: '编辑定时质量报告' }); await modal.waitFor()
    const name = modal.locator('.arco-form-item').filter({ hasText: '计划名称' }).locator('input'); await name.fill('我的保留草稿')
    await modal.getByRole('button', { name: '确定', exact: true }).click()
    await modal.getByRole('button', { name: '读取最新版本', exact: true }).waitFor(); assert.equal(await name.inputValue(), '我的保留草稿')
    assert.equal(hits.filter(hit => hit.method === 'PUT').length, 1)
    await modal.getByRole('button', { name: '读取最新版本', exact: true }).click(); await modal.getByText(/其他成员的新计划/).waitFor()
    assert.equal(await name.inputValue(), '我的保留草稿'); assert.equal(hits.filter(hit => hit.method === 'PUT').length, 1)
    await modal.getByRole('button', { name: '确定', exact: true }).click(); await modal.waitFor({ state: 'hidden' })
    assert.deepEqual(hits.filter(hit => hit.method === 'PUT').map(hit => hit.payload.expectedVersion), [0, 1])
  })
} finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(item => item.status === 'PASS').length; result.failed = result.cases.length - result.passed
  await fs.writeFile(path.join(out, 'headless-ui.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n')
  if (result.failed) process.exitCode = 1
}
