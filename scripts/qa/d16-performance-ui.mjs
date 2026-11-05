// Headless performance page acceptance uses controlled API fixtures; native HTTP has a separate QA script.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261102/d16-performance-fixes-r1'), dist = path.join(root, 'dist')
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
async function run(id, operation) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } }), writes = [], errors = []
  await context.route('**/api/**', async route => {
    const r = route.request(), p = new URL(r.url()).pathname; let data
    if (r.method() === 'POST') { const input = r.postDataJSON(); writes.push(input); data = { id: 'saved', ...input, version: 1, threadGroupCount: input.threadGroups.length } }
    else if (p.endsWith('/auth/me')) data = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp }
    else if (p.endsWith('/auth/csrf')) data = { token: 'fixture', headerName: 'X-CSRF' }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA', code: 'QA' }], projects: { o: [{ id: 'p', name: 'QA项目', code: 'QA', status: 'ACTIVE', createdAt: stamp, updatedAt: stamp }] } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else data = []
    await route.fulfill({ json: { data } })
  })
  const page = await context.newPage(); page.setDefaultTimeout(10_000); page.on('pageerror', error => errors.push(error.message))
  try {
    await page.goto(origin + '/performance')
    await page.getByRole('button', { name: '新建压测计划', exact: true }).click()
    await page.getByPlaceholder('例如：订单核心链路基准压测').fill('QA')
    const dialog = page.locator('.arco-modal').filter({ has: page.getByPlaceholder('例如：订单核心链路基准压测') })
    const save = () => dialog.locator('.arco-modal-footer .arco-btn-primary').click()
    const field = (name, index = 0) => page.getByLabel('性能 ' + name, { exact: true }).nth(index).locator('input')
    await operation(page, dialog, save, field, writes); assert.deepEqual(errors, []); result.cases.push({ id, status: 'PASS' })
  } catch (error) { result.cases.push({ id, status: 'FAIL', error: error.message, browserErrors: errors }) }
  finally { await context.close() }
}
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('TC-D16PERF-57', async (page, dialog, save, field, writes) => {
    for (const raw of ['1.5', '1.0000000000000001', '100001']) {
      await field('threadCount').fill(raw); await save(); await page.locator('.arco-message').filter({ hasText: 'threadCount' }).first().waitFor()
      assert.equal(writes.length, 0); assert.equal(await field('threadCount').inputValue(), raw)
    }
    await field('threadCount').fill('10'); await field('expectedStatusCode').fill('200.5'); await save()
    await page.locator('.arco-message').filter({ hasText: 'expectedStatusCode' }).first().waitFor(); assert.equal(writes.length, 0)
  })
  await run('TC-D16PERF-58', async (page, dialog, save, field, writes) => {
    await field('threadCount').fill('1e1'); await field('durationSeconds').fill('10.0'); await field('expectedStatusCode').fill('2e2')
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/plans') && r.request().method() === 'POST'), save()])
    const g = writes.at(-1).threadGroups[0]; assert.equal(g.threadCount, 10); assert.equal(g.durationSeconds, 10); assert.equal(g.expectedStatusCode, 200)
  })
  await run('TC-D16PERF-59', async (page, dialog, save, field, writes) => {
    await field('errorThresholdPercent').fill('2.501'); await save()
    await page.locator('.arco-message').filter({ hasText: 'errorThresholdPercent' }).first().waitFor(); assert.equal(writes.length, 0); assert.equal(await field('errorThresholdPercent').inputValue(), '2.501')
    await field('errorThresholdPercent').fill('2.50'); await Promise.all([page.waitForResponse(r => r.url().endsWith('/plans') && r.request().method() === 'POST'), save()]); assert.equal(writes.at(-1).threadGroups[0].errorThresholdPercent, 2.5)
  })
  await run('TC-D16PERF-60', async (page, dialog, save, field, writes) => {
    for (const name of ['targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers', 'errorThresholdPercent']) await field(name).fill('')
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/plans') && r.request().method() === 'POST'), save()])
    const g = writes.at(-1).threadGroups[0]; for (const name of ['targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers', 'errorThresholdPercent']) assert.equal(g[name], undefined)
  })
  await run('TC-D16PERF-61', async (page, dialog, save, field, writes) => {
    await field('threadCount').fill('1.5'); await dialog.getByRole('button', { name: '添加线程组' }).click(); await field('threadCount', 1).fill('20.0')
    await dialog.locator('.thread-group-form').first().getByRole('button', { name: '删除', exact: true }).click()
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/plans') && r.request().method() === 'POST'), save()]); assert.equal(writes.at(-1).threadGroups[0].threadCount, 20)
  })
} finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(c => c.status === 'PASS').length; result.failed = result.cases.length - result.passed
  await fs.writeFile(path.join(out, 'headless-ui.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n'); if (result.failed) process.exitCode = 1
}
