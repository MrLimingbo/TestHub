import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import crypto from 'node:crypto'
import { fileURLToPath, URL } from 'node:url'
import { performance } from 'node:perf_hooks'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261029/p1-fixes-r1')
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
  let reset = false
  await context.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname, method = request.method(); hits.push({ path: p, method })
    let status = 200, data
    if (p.endsWith('/auth/me')) { status = reset && options.self ? 401 : 200; data = { id: 'me', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp } }
    else if (p.endsWith('/auth/csrf')) data = { headerName: 'X-CSRF', token: 'fixture' }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA组织', code: 'QA' }], projects: { o: [{ id: 'p', name: 'QA项目', code: 'P', status: 'ACTIVE', description: '', createdAt: stamp, updatedAt: stamp }] } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else if (p.endsWith('/accounts')) data = { items: [{ id: options.self ? 'me' : 'other', account: 'qa-account', email: 'qa@example.test', displayName: 'QA', enabled: true, createdAt: stamp, updatedAt: stamp }], total: 1, page: 1, pageSize: 10 }
    else if (p.endsWith('/password') && method === 'PUT') { status = options.failure ? 503 : 204; if (!options.failure) reset = true }
    else if (p.endsWith('/environments')) data = [{ id: 'env', name: '测试', environmentCode: 'test', enabled: true, defaultEnvironment: true }]
    else if (p.endsWith('/parameters')) data = { environments: ['test'], parameters: [] }
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
  } catch (error) { result.cases.push({ id, title, status: 'FAIL', error: error.message, browserErrors: errors }) }
  finally { await context.close() }
}
const history = state => ({ id: 'h', projectId: 'p', method: 'POST', protocol: 'HTTP', requestUrl: 'https://example.test/p1-replay', responseStatus: 200, durationMs: 1, responseSize: 0, result: 'COMPLETED', createdAt: stamp, replayStatus: state, replayExpiresAt: '2026-10-28T00:00:00Z', replayDraft: null })
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('P1-UI-01', '加密字段、项目写者、30天及旧密文例外告知', '/api-testing/quick-request', {}, async page => {
    await page.locator('.replay-policy-note').waitFor(); const text = await page.locator('.replay-policy-note').innerText()
    for (const expected of ['认证和正文', '项目写权限', '30天', '旧历史密文', '暂不自动删除', '24小时']) assert.ok(text.includes(expected))
  })
  for (const state of ['EXPIRED', 'UNKNOWN_CREATED_AT', 'UNREADABLE']) await run('P1-UI-' + state, '快捷树拒绝不可用回放：' + state, '/api-testing/quick-request', { history: history(state) }, async (page, hits) => {
    const row = page.locator('.quick-request-history').filter({ hasText: 'p1-replay' }); await row.waitFor(); await row.click()
    await page.locator('.arco-message').filter({ hasText: state === 'EXPIRED' ? '到期' : state === 'UNREADABLE' ? '解密' : '时间' }).waitFor()
    assert.equal(hits.filter(hit => hit.path.endsWith('/execute')).length, 0)
  })
  for (const mode of ['self', 'other', 'failure']) await run('P1-UI-RESET-' + mode, '账号改密交互：' + mode, '/system-settings/accounts', { self: mode !== 'other', failure: mode === 'failure' }, async (page, hits) => {
    await page.getByRole('button', { name: '修改密码', exact: true }).click(); const modal = page.locator('.arco-modal:visible'); await modal.waitFor()
    const password = crypto.randomUUID(); await modal.getByPlaceholder('请输入新密码', { exact: true }).fill(password); await modal.getByPlaceholder('请再次输入新密码', { exact: true }).fill(password)
    await modal.getByRole('button', { name: '确定', exact: true }).click()
    if (mode === 'self') await page.waitForURL('**/login')
    else if (mode === 'failure') { await page.locator('.arco-message').filter({ hasText: 'QA受控失败' }).waitFor(); assert.equal(await modal.getByPlaceholder('请输入新密码', { exact: true }).inputValue(), password) }
    else { await modal.waitFor({ state: 'hidden' }); assert.ok(page.url().endsWith('/system-settings/accounts')) }
    assert.equal(hits.filter(hit => hit.method === 'PUT' && hit.path.endsWith('/password')).length, 1)
  })
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(row => row.status === 'PASS').length; result.failed = result.cases.filter(row => row.status === 'FAIL').length
  result.status = result.cases.length === 7 && result.failed === 0 ? 'PASS' : 'FAIL'
  await fs.writeFile(path.join(out, 'browser-results.json'), JSON.stringify(result, null, 2) + '\n')
}
if (result.status !== 'PASS') process.exitCode = 1
