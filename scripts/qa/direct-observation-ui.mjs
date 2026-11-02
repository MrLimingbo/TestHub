/* global URL, performance, navigator, window */
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261029/direct-fixes-r1')
const dist = path.join(root, 'dist')
const stamp = '2026-10-29T00:00:00Z'
const result = { date: '2026-10-29', headless: true, isolatedHttpFixtures: true, realBackendAcceptance: false, results: [], cleanup: false }
const server = http.createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://qa.invalid').pathname)
    let file = path.resolve(dist, '.' + pathname)
    if (!file.startsWith(dist + path.sep)) file = path.join(dist, 'index.html')
    if (!path.extname(file)) file = path.join(dist, 'index.html')
    const type = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' }[path.extname(file)] || 'application/octet-stream'
    const body = await fs.readFile(file)
    res.writeHead(200, { 'Content-Type': type }); res.end(body)
  } catch { res.writeHead(404); res.end() }
})
await fs.mkdir(out, { recursive: true })
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
let browser
const answer = (data, status = 200) => ({ status, json: status < 400 ? { data } : { message: 'QA受控失败' } })
function gate() {
  let release, entered
  const pending = new Promise(resolve => { release = resolve })
  const started = new Promise(resolve => { entered = resolve })
  return { started, release, wait() { entered(); return pending } }
}
const task = { id: 't1', projectId: 'p1', organizationId: 'o1', name: 'QA登记', taskType: 'API', status: 'QUEUED', progress: 0, version: 1, createdAt: stamp, updatedAt: stamp }
const qualitySummary = { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }
const scene = { id: 's1', projectId: 'p1', name: 'QA场景', description: '', version: 1, environmentCode: 'test', failureStrategy: 'STOP', variables: {}, preRequestScript: '', postResponseScript: '', createdAt: stamp, updatedAt: stamp, steps: [{ id: 'wait', type: 'WAIT', name: '等待', config: { delayMillis: 1 } }] }
const debugResult = id => ({ historyId: id, success: true, statusCode: 200, statusText: 'OK', durationMs: 2, responseHeaders: [], body: id, truncated: false, errorMessage: null })
function fixture(count = 2) {
  const projects = Array.from({ length: count }, (_, i) => ({ id: `p${i + 1}`, organizationId: 'o1', name: `项目${i + 1}`, code: `P${i + 1}`, status: 'ACTIVE', description: '', createdByName: 'QA', createdAt: stamp, updatedAt: stamp }))
  const context = { organizations: [{ id: 'o1', name: 'QA组织', code: 'QA' }], projects: { o1: projects } }
  return {
    context, hits: [], hook: () => undefined, gates: [],
    gate() { const value = gate(); this.gates.push(value); return value },
    async route(route) {
      const request = route.request(), url = new URL(request.url()), pathname = url.pathname, method = request.method()
      this.hits.push({ pathname, method, query: url.search, body: request.postData() })
      let response = await this.hook(pathname, method, request)
      if (!response) {
        const data = pathname.endsWith('/auth/me') ? { id: 'qa-user', account: 'qa', email: 'qa@example.test', displayName: '隔离QA', authenticatedAt: stamp }
          : pathname.endsWith('/access/context') ? context
            : pathname.endsWith('/auth/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
              : pathname.endsWith('/capabilities') ? { systemAdministration: true }
                : pathname.endsWith('/audit-logs') ? [{ id: 'audit1', action: 'QA_ACTION', resourceType: 'PROJECT', outcome: 'SUCCESS', requestId: 'qa-request', createdAt: stamp }]
                  : pathname.endsWith('/environments') ? [{ id: 'env1', name: '测试环境', environmentCode: 'test', enabled: true, defaultEnvironment: true, baseUrl: 'https://example.test' }]
                    : pathname.endsWith('/parameters') ? { environments: ['test'], parameters: [] }
                      : pathname.endsWith('/secrets') ? { environments: ['test'], secrets: [] }
                        : pathname.endsWith('/api-design/preferences') ? { revision: 0, state: { folders: [], hiddenIds: [], folderIds: {}, displayMode: 'NAME' } }
                          : pathname.endsWith('/tasks') ? method === 'POST' ? task : [task]
                            : pathname.endsWith('/reports/quality') ? { from: '2026-10-01', to: '2026-10-29', generatedAt: stamp, projectCount: 1, summary: qualitySummary, trend: [], projects: [] }
                              : pathname.endsWith('/adapter-definitions') ? [{ type: 'OIDC', displayName: 'OIDC', category: 'IDENTITY', requiredFields: ['clientId'], description: 'QA适配器' }]
                                : pathname.endsWith('/signing-keys') ? [{ id: 'key1', keyId: 'qa.key', displayName: 'QA公钥', enabled: true, createdAt: stamp }]
                                  : pathname.endsWith('/api-automation/scenarios') ? [scene]
                                    : pathname.endsWith('/api-automation/scenarios/s1') ? scene
                                      : pathname.includes('/mock-shares/') ? { shareKey: 'qa', shareName: 'QA分享', serviceName: 'QA服务', publicKey: 'qa-public', serviceEnabled: true, smartExampleEnabled: false, expiresAt: null, rules: [] }
                                        : pathname.endsWith('/api-debug/execute') ? debugResult('run-QA') : []
        response = answer(data)
      }
      await route.fulfill(response)
    },
  }
}
const writes = f => f.hits.filter(hit => ['POST', 'PUT', 'DELETE'].includes(hit.method))
const queries = f => f.hits.filter(hit => hit.pathname.endsWith('/reports/quality') || hit.pathname.endsWith('/reports/quality/export'))
async function waitUntil(fn, description) {
  for (let n = 0; n < 150; n++) { if (await fn()) return; await delay(40) }
  throw new Error('UI condition timed out: ' + description)
}
async function run(id, observation, title, routePath, operation, count = 2) {
  const f = fixture(count), context = await browser.newContext({ viewport: { width: 1600, height: 1000 } }), errors = []
  await context.route('**/api/**', route => f.route(route))
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message))
  page.setDefaultTimeout(7000)
  const start = performance.now()
  try {
    await page.goto(origin + routePath); await operation(page, f)
    assert.deepEqual(errors, [], 'Unhandled browser errors')
    result.results.push({ id, observation, title, status: 'PASS', durationMs: Math.round(performance.now() - start), browserErrors: errors })
  } catch (error) {
    result.results.push({ id, observation, title, status: 'FAIL', error: error.message, durationMs: Math.round(performance.now() - start), browserErrors: errors })
  } finally {
    for (const pending of f.gates) pending.release(answer([]))
    await context.close()
  }
}
const modal = page => page.locator('.arco-modal:visible')
async function fillTask(page, name) { await modal(page).getByPlaceholder('例如：交易中台接口回归').fill(name) }
async function openTask(page) { await page.getByRole('button', { name: '新建任务', exact: true }).last().click(); await modal(page).waitFor() }

try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('UI-01', 'OBS-03C-E007', '审计旧数据、失败标识和成功重试', '/audit', async (page, f) => {
    await page.locator('.audit-last-success').waitFor(); f.hook = pathname => pathname.endsWith('/audit-logs') ? answer({}, 503) : undefined
    await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.locator('.audit-load-error').waitFor()
    assert.match(await page.locator('.audit-load-error').innerText(), /旧数据/); assert.match(await page.locator('.data-panel').innerText(), /QA_ACTION/)
    f.hook = () => undefined; await page.locator('.audit-load-error').getByRole('button', { name: '重试' }).click()
    await page.locator('.audit-load-error').waitFor({ state: 'hidden' })
  })
  await run('UI-02', 'OBS-13-E015', '51项目初载、显式选择、清空请求门控', '/reports', async (page, f) => {
    await page.getByRole('status').filter({ hasText: '请先选择1至50个项目' }).waitFor(); assert.equal(queries(f).length, 0)
    const select = page.getByLabel('筛选统计项目'); await select.click(); await page.locator('.arco-select-option:visible').filter({ hasText: /^项目1$/ }).click()
    await waitUntil(() => queries(f).length === 1, 'one selected project query')
    assert.match(queries(f)[0].query, /projectIds=p1(?:&|$)/)
    await page.keyboard.press('Escape'); await select.hover(); await select.locator('.arco-select-view-clear-btn').click()
    await page.getByRole('status').filter({ hasText: '请先选择' }).waitFor(); const before = queries(f).length
    assert.equal(await page.getByRole('button', { name: '导出 CSV', exact: true }).count(), 0)
    assert.equal(await page.getByRole('button', { name: '刷新', exact: true }).isDisabled(), true); assert.equal(queries(f).length, before)
  }, 51)
  await run('UI-03', 'OBS-13-E009', '任务列表刷新时可打开新草稿', '/tasks', async (page, f) => {
    await page.getByText('QA登记', { exact: true }).waitFor(); const pending = f.gate()
    f.hook = (pathname, method) => pathname.endsWith('/tasks') && method === 'GET' ? pending.wait() : undefined
    await page.getByRole('button', { name: '刷新', exact: true }).click(); await pending.started
    await openTask(page); await fillTask(page, '刷新中新草稿'); pending.release(answer([], 503))
    await page.getByText('QA受控失败', { exact: true }).waitFor(); assert.equal(await modal(page).getByPlaceholder('例如：交易中台接口回归').inputValue(), '刷新中新草稿')
  })
  await run('UI-04', 'OBS-13-E017', '旧保存结束不能解锁或关闭新草稿保存', '/tasks', async (page, f) => {
    await page.getByText('QA登记', { exact: true }).waitFor(); const old = f.gate(), latest = f.gate(); let count = 0
    f.hook = (pathname, method) => pathname.endsWith('/tasks') && method === 'POST' ? (++count === 1 ? old.wait() : latest.wait()) : undefined
    await openTask(page); await fillTask(page, '旧A'); await modal(page).getByRole('button', { name: '确定', exact: true }).click(); await old.started
    await modal(page).getByRole('button', { name: '取消', exact: true }).click(); await modal(page).waitFor({ state: 'hidden' })
    await openTask(page); await fillTask(page, '新B'); await modal(page).getByRole('button', { name: '确定', exact: true }).click(); await latest.started
    old.release(answer(task)); await delay(100)
    assert.equal(await modal(page).getByPlaceholder('例如：交易中台接口回归').inputValue(), '新B')
    assert.match(await modal(page).getByRole('button', { name: '确定', exact: true }).getAttribute('class'), /arco-btn-loading/)
    latest.release(answer(task)); await modal(page).waitFor({ state: 'hidden' }); assert.equal(writes(f).length, 2)
  })
  await run('UI-05', 'OBS-07-E006', '真实快捷请求入口离页完成仍提示原项目和执行', '/api-testing/quick-request?protocol=HTTP&url=https%3A%2F%2Fexample.test%2Fqa', async (page, f) => {
    const old = f.gate(); f.hook = pathname => pathname.endsWith('/api-debug/execute') ? old.wait() : undefined
    await page.getByRole('button', { name: '发送', exact: true }).waitFor(); await page.getByRole('button', { name: '发送', exact: true }).click(); await old.started
    await page.locator('.topbar').getByLabel('选择项目').click(); await page.locator('.arco-select-option').filter({ hasText: /^项目2$/ }).click()
    await waitUntil(async () => (await page.locator('.topbar').innerText()).includes('项目2'), 'switch to project2')
    old.release(answer(debugResult('run-origin-A')))
    const message = page.locator('.arco-message').filter({ hasText: 'run-origin-A' }); await message.waitFor()
    assert.match(await message.innerText(), /项目1.*p1/); assert.equal(f.hits.filter(hit => hit.pathname.endsWith('/api-debug/execute')).length, 1)
    assert.equal(await page.locator('.response-code').filter({ hasText: 'run-origin-A' }).count(), 0)
  })
  await run('UI-06', 'OBS-08-E012', '本页提交提示范围准确且刷新重置', '/api-automation', async page => {
    await page.getByText('本页提交提示', { exact: true }).waitFor(); const row = page.locator('.run-switch-row')
    assert.match(await row.innerText(), /刷新后重置/); assert.match(await row.innerText(), /不发送外部通知/)
    const toggle = page.getByRole('switch', { name: '本页提交提示' }); await toggle.click(); assert.equal(await toggle.getAttribute('aria-checked'), 'true')
    await page.reload(); await page.getByText('本页提交提示', { exact: true }).waitFor(); assert.equal(await page.getByRole('switch', { name: '本页提交提示' }).getAttribute('aria-checked'), 'false')
  })
  await run('UI-07', 'OBS-09-E012', '剪贴板拒绝、不可用、恢复及手选完整地址', '/mock/share/qa', async page => {
    const input = page.getByLabel('公开调用地址，可手动复制'); await input.waitFor()
    await page.evaluate(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('QA拒绝') } } }) })
    await page.getByRole('button', { name: '复制', exact: true }).click(); await page.getByRole('alert').filter({ hasText: '手动复制' }).waitFor()
    await input.focus(); assert.equal(await input.evaluate(element => element.selectionEnd - element.selectionStart), (await input.inputValue()).length)
    await page.evaluate(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined }) })
    await page.getByRole('button', { name: '复制', exact: true }).click(); await page.getByRole('alert').filter({ hasText: '手动复制' }).waitFor()
    await page.evaluate(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async value => { window.__qaClipboardValue = value } } }) })
    await page.getByRole('button', { name: '复制', exact: true }).click(); await page.getByRole('status').filter({ hasText: '已复制' }).waitFor()
    assert.equal(await page.evaluate(() => window.__qaClipboardValue), origin + '/api/v1/mock/qa-public')
  })
  await run('UI-08', 'OBS-14-E004', '通知刷新中可新建且迟到失败保留草稿', '/integrations', async (page, f) => {
    await page.getByRole('heading', { name: '通知与集成', exact: true }).waitFor(); await page.getByRole('button', { name: '刷新记录', exact: true }).waitFor()
    await waitUntil(() => f.hits.filter(hit => hit.pathname.endsWith('/notification-templates')).length > 0, 'initial templates')
    const pending = f.gate(); f.hook = pathname => pathname.endsWith('/notification-templates') ? pending.wait() : undefined
    await page.getByRole('button', { name: '刷新记录', exact: true }).click(); await pending.started
    await page.getByRole('button', { name: '新建模板', exact: true }).last().click(); await modal(page).getByPlaceholder('例如：接口回归失败通知').fill('新通知草稿')
    pending.release(answer([], 503)); await page.locator('.arco-alert').filter({ hasText: 'QA受控失败' }).waitFor()
    assert.equal(await modal(page).getByPlaceholder('例如：接口回归失败通知').inputValue(), '新通知草稿'); assert.equal(writes(f).length, 0)
  })
  for (const kind of ['package', 'adapter']) await run(kind === 'package' ? 'UI-09' : 'UI-10', kind === 'package' ? 'OBS-14-E014' : 'OBS-14-E019', '插件中心刷新时可新建且权限撤销仍阻止写入：' + kind, '/plugins', async (page, f) => {
    const title = kind === 'package' ? '安装签名包' : '新建适配器'
    await page.getByRole('button', { name: title, exact: true }).waitFor(); const pending = f.gate()
    f.hook = pathname => pathname.endsWith('/capabilities') ? pending.wait() : undefined
    await page.getByRole('button', { name: '刷新插件与适配器', exact: true }).click(); await pending.started
    await page.getByRole('button', { name: title, exact: true }).click(); await modal(page).waitFor()
    pending.release(answer({ systemAdministration: false })); await modal(page).waitFor({ state: 'hidden' })
    assert.equal(await page.getByRole('button', { name: title, exact: true }).count(), 0); assert.equal(writes(f).length, 0)
  })
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.results.filter(row => row.status === 'PASS').length; result.failed = result.results.filter(row => row.status === 'FAIL').length
  result.status = result.results.length === 10 && !result.failed ? 'PASS' : 'FAIL'
  await fs.writeFile(path.join(out, 'browser-results.json'), JSON.stringify(result, null, 2) + '\n')
}
if (result.status !== 'PASS') process.exitCode = 1
