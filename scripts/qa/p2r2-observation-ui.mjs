/* global document, localStorage, sessionStorage */
// Browser acceptance uses controlled API fixtures. It does not claim real Runner or remote WS coverage.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261030/p2-round2-fixes-r1'), dist = path.join(root, 'dist')
const stamp = '2026-10-30T00:00:00Z'
const result = { headless: true, isolatedHttpFixtures: true, realBackendAcceptance: false, cases: [], cleanup: false }
const server = http.createServer(async (req, res) => {
  try {
    let file = path.resolve(dist, '.' + decodeURIComponent(new URL(req.url, 'http://qa.invalid').pathname))
    if (!file.startsWith(dist + path.sep) || !path.extname(file)) file = path.join(dist, 'index.html')
    res.writeHead(200, { 'Content-Type': { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' }[path.extname(file)] || 'application/octet-stream' })
    res.end(await fs.readFile(file))
  } catch { res.writeHead(404); res.end() }
})
await fs.mkdir(out, { recursive: true }); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
let browser
async function run(id, title, routePath, operation) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } }), hits = [], errors = []
  const state = { csrfGate: null, csrfRelease: null, connections: 0 }
  await context.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname, method = request.method()
    const hit = { path: p, method, query: url.search }; hits.push(hit)
    if (method !== 'GET') hit.payload = request.postDataJSON()
    let data
    if (p.endsWith('/auth/me')) data = { id: 'me', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp }
    else if (p.endsWith('/auth/csrf')) { if (state.csrfGate) await state.csrfGate; data = { headerName: 'X-CSRF', token: 'fixture' } }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA组织', code: 'QA' }], projects: { o: ['p1', 'p2'].map(id => ({ id, name: id === 'p1' ? 'QA项目一' : 'QA项目二', code: id, status: 'ACTIVE', description: '', createdAt: stamp, updatedAt: stamp })) } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else if (p.endsWith('/tasks')) data = [{ id: 'task', projectId: p.includes('/p2/') ? 'p2' : 'p1', name: 'QA任务', taskType: 'API', status: 'QUEUED', progress: 0, version: 1, createdAt: stamp, updatedAt: stamp }]
    else if (p.endsWith('/environments')) data = []
    else if (p.endsWith('/parameters')) data = { environments: [], parameters: [] }
    else if (p.endsWith('/secrets')) data = { environments: [], secrets: [] }
    else if (p.endsWith('/workspace-preferences')) data = { revision: 0, state: { folders: [], hiddenIds: [], folderIds: {}, displayMode: 'NAME' } }
    else if (p.endsWith('/catalogs')) data = [{ id: 'root', name: '源目录', parentId: null, sortOrder: 0, createdAt: stamp, updatedAt: stamp }]
    else if (p.endsWith('/definitions')) data = [{ id: 'a', catalogId: 'root', method: 'POST', path: '/runtime', name: '交接接口', currentVersionId: 'v', versionCount: 1, status: 'DRAFT', createdAt: stamp, updatedAt: stamp }]
    else if (p.endsWith('/versions')) data = [{ id: 'v', versionName: '1.0.0', requestHeaders: '[{"name":"X-Run","type":"string","required":false,"example":"qa-header-fixture","description":""}]', requestQuery: '[]', requestPath: '[]', requestBody: 'qa-body-fixture', responseBody: '{}', exampleResponse: '{"statusCode":200}', status: 'DRAFT', publishedAt: null, createdAt: stamp }]
    else if (p.endsWith('/execute')) data = { historyId: 'run', success: true, statusCode: 200, durationMs: 1, body: '{}', responseHeaders: [], truncated: false }
    else if (p.endsWith('/connect')) data = { id: 'ws-' + (++state.connections), status: 'OPEN', target: 'wss://8.8.8.8/echo', messageCount: 0, connectedAt: stamp, autoReconnect: false }
    else if (p.endsWith('/close')) data = { id: p.split('/').at(-2), status: 'CLOSED', messageCount: 0, connectedAt: stamp, autoReconnect: false }
    else if (/\/sessions\/ws-\d+$/.test(p)) data = { id: p.split('/').at(-1), status: 'OPEN', messageCount: 0, connectedAt: stamp, autoReconnect: false }
    else if (p.endsWith('/audit-logs/actions')) data = ['QA_A', 'QA_B', 'QA_OLDER_THAN_200']
    else if (p.endsWith('/audit-logs')) data = [{ id: 'audit', action: url.searchParams.get('action') || 'QA_A', resourceType: 'QA', outcome: 'SUCCESS', requestId: 'qa-fixture', createdAt: stamp }]
    else data = []
    await route.fulfill({ json: { data } })
  })
  const page = await context.newPage(); page.setDefaultTimeout(10_000); page.on('pageerror', error => errors.push(error.message))
  try { await page.goto(origin + routePath); await operation(page, hits, state); assert.deepEqual(errors, []); result.cases.push({ id, title, status: 'PASS' }) }
  catch (error) { result.cases.push({ id, title, status: 'FAIL', error: error.message, browserErrors: errors }) }
  finally { state.csrfRelease?.(); await context.close() }
}
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('TC-P2R2-D20-01', 'CSRF等待中切换项目，业务请求不发出', '/tasks', async (page, hits, state) => {
    await page.getByRole('button', { name: '标记执行中', exact: true }).waitFor()
    state.csrfGate = new Promise(resolve => { state.csrfRelease = resolve })
    await page.getByRole('button', { name: '标记执行中', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.arco-btn-loading'))
    await page.getByLabel('选择项目').click(); await page.locator('.arco-select-option:visible').filter({ hasText: 'QA项目二' }).click()
    state.csrfRelease(); await page.getByRole('button', { name: '标记执行中', exact: true }).waitFor()
    assert.equal(hits.filter(hit => hit.method === 'PUT').length, 0)
  })
  await run('TC-P2R2-D13-01/02/03', '嵌入式旧摘要必须重建核对后手动发送', '/api-testing/quick-request?method=GET&protocol=HTTP&url=https%3A%2F%2F8.8.8.8%2Flegacy%3Ftoken%3D***&legacyHistory=1', async (page, hits) => {
    await page.locator('.legacy-history-review').waitFor()
    await page.getByRole('button', { name: '发送', exact: true }).click(); await page.locator('.arco-message').filter({ hasText: '重建' }).first().waitFor()
    assert.equal(hits.filter(hit => hit.path.endsWith('/execute')).length, 0)
    await page.getByText('我已核对重建请求，下一步由我手动发送', { exact: true }).click()
    const url = page.getByPlaceholder('输入 http 或 https 开头的完整 URL'); await url.fill('https://8.8.8.8/legacy?token=rebuilt'); await url.blur()
    await page.getByRole('button', { name: '发送', exact: true }).click(); await page.getByText('200', { exact: true }).first().waitFor()
    const sends = hits.filter(hit => hit.path.endsWith('/execute')); assert.equal(sends.length, 1); assert.equal(sends[0].payload.auth.type, 'NONE'); assert.equal(sends[0].payload.body, '')
  })
  await run('TC-P2R2-D14-01/03', '草稿改变后禁用旧连接发送，显式替换先关闭自有连接', '/api-testing/quick-request?protocol=WEBSOCKET&url=wss%3A%2F%2F8.8.8.8%2Fecho', async (page, hits) => {
    await page.getByRole('button', { name: '连接', exact: true }).first().click(); await page.getByText('OPEN', { exact: true }).first().waitFor()
    const url = page.getByPlaceholder('wss://ws.postman-echo.com/raw'); await url.fill('wss://8.8.8.8/other'); await url.blur()
    assert.equal(await page.getByRole('button', { name: /发送文本消息/ }).isDisabled(), true)
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/connect') && response.status() === 200), page.getByRole('button', { name: '连接', exact: true }).first().click()])
    assert.deepEqual(hits.filter(hit => hit.method === 'POST' && /\/(connect|close)$/.test(hit.path)).map(hit => hit.path.split('/').at(-1)), ['connect', 'close', 'connect'])
    assert.ok(hits.some(hit => hit.path.endsWith('/sessions/ws-1/close')))
  })
  await run('TC-P2R2-D15-01/02/06', '实际源页面到高级调试传递认证和完整请求且不落Web Storage', '/api-testing/interfaces', async (page, hits) => {
    await page.getByText('交接接口', { exact: true }).first().click(); await page.locator('.arco-tabs-tab:visible').filter({ hasText: /^调试$/ }).click()
    await page.getByLabel('请求服务地址').locator('input').fill('https://8.8.8.8')
    await page.locator('.arco-tabs-tab:visible').filter({ hasText: /^Auth$/ }).click()
    const auth = page.locator('.arco-form-item').filter({ hasText: '认证类型' }); await auth.locator('.arco-select-view').click(); await page.locator('.arco-select-option:visible').filter({ hasText: 'Bearer Token' }).click()
    await page.locator('.arco-form-item').filter({ hasText: /^Token/ }).locator('input').fill('qa-token-fixture')
    await page.getByRole('button', { name: '高级调试', exact: true }).click(); await page.waitForURL('**/quick-request?**')
    const u = new URL(page.url()); assert.deepEqual([...u.searchParams.keys()].sort(), ['handoff', 'sourceApiId']); assert.equal(u.searchParams.get('sourceApiId'), 'a')
    const storage = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))
    for (const marker of ['qa-token-fixture', 'qa-header-fixture', 'qa-body-fixture']) assert.ok(!storage.includes(marker))
    assert.equal(hits.filter(hit => hit.path.endsWith('/execute')).length, 0)
    await page.getByRole('button', { name: '发送', exact: true }).click(); await page.getByText('200', { exact: true }).first().waitFor()
    const p = hits.find(hit => hit.path.endsWith('/execute')).payload
    assert.equal(p.auth.type, 'BEARER'); assert.equal(p.auth.token, 'qa-token-fixture'); assert.equal(p.body, 'qa-body-fixture'); assert.ok(p.headers.some(h => h.name === 'X-Run' && h.value === 'qa-header-fixture'))
  })
  await run('TC-P2R2-D04-01/02', '真实Arco下拉切换A到B仍可选择超出行限制的稀有动作', '/audit', async (page, hits) => {
    const select = page.locator('.audit-toolbar .arco-select-view'); await select.click(); await page.locator('.arco-select-option:visible').filter({ hasText: /^QA_A$/ }).click()
    await page.waitForFunction(() => document.querySelector('.audit-last-success')?.textContent.includes('QA_A'))
    await select.click(); await page.locator('.arco-select-option:visible').filter({ hasText: /^QA_B$/ }).click()
    await page.waitForFunction(() => document.querySelector('.audit-last-success')?.textContent.includes('QA_B'))
    await select.click(); await page.locator('.arco-select-option:visible').filter({ hasText: 'QA_OLDER_THAN_200' }).waitFor()
    assert.ok(hits.some(hit => hit.path.endsWith('/audit-logs') && hit.query.includes('action=QA_B') && hit.query.includes('limit=100')))
  })
  await run('TC-P2R2-D18-05', '页面对象说明与入队冻结规则一致', '/ui-automation', async page => {
    await page.locator('.arco-tabs-tab:visible').filter({ hasText: /^页面对象$/ }).click(); await page.getByText('场景保存页面对象引用，执行清单在入队时解析并冻结实际定位器。', { exact: true }).waitFor()
    assert.ok(!(await page.locator('body').innerText()).includes('场景保存时会冻结实际定位器'))
  })
} finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(item => item.status === 'PASS').length; result.failed = result.cases.length - result.passed
  await fs.writeFile(path.join(out, 'headless-ui.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n')
  if (result.failed) process.exitCode = 1
}
