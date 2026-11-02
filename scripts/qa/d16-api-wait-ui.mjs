// Browser acceptance uses controlled API fixtures. It does not claim real Runner or remote WS coverage.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261102/d16-api-automation-fixes-r1'), dist = path.join(root, 'dist')
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
async function run(id, initialDelay, operation) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } }), writes = [], errors = []
  let current = { id: 's', projectId: 'p', name: 'WAIT验收', description: '', environmentCode: 'test', failureStrategy: 'STOP', version: 1, variables: {}, preRequestScript: '', postResponseScript: '', steps: [{ id: 'get', type: 'REQUEST', name: 'HTTP', config: { method: 'GET', url: 'https://8.8.8.8/qa' } }, { id: 'wait', type: 'WAIT', name: '等待', config: { delayMillis: initialDelay } }], createdAt: stamp, updatedAt: stamp }
  await context.route('**/api/**', async route => {
    const r = route.request(), p = new URL(r.url()).pathname; let data
    if (r.method() === 'PUT') { const input = r.postDataJSON(); writes.push(input); current = { ...current, ...input.scenario, version: current.version + 1 }; data = current }
    else if (p.endsWith('/auth/me')) data = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp }
    else if (p.endsWith('/auth/csrf')) data = { token: 'fixture', headerName: 'X-CSRF' }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA', code: 'QA' }], projects: { o: [{ id: 'p', name: 'QA项目', code: 'QA', status: 'ACTIVE', createdAt: stamp, updatedAt: stamp }] } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else if (p.endsWith('/environments')) data = [{ id: 'env', name: 'QA', environmentCode: 'test', enabled: true, defaultEnvironment: true }]
    else if (p.endsWith('/scenarios')) data = [current]
    else if (p.endsWith('/scenarios/s')) data = current
    else data = []
    await route.fulfill({ json: { data } })
  })
  const page = await context.newPage(); page.setDefaultTimeout(10_000); page.on('pageerror', error => errors.push(error.message))
  try {
    await page.goto(origin + '/api-automation'); await page.getByText('WAIT验收', { exact: true }).first().click()
    await page.locator('.scenario-flow-node').filter({ hasText: '等待' }).click()
    const field = page.getByLabel('WAIT 延迟（毫秒）').locator('input')
    await field.waitFor(); await operation(page, field, writes); assert.deepEqual(errors, []); result.cases.push({ id, status: 'PASS' })
  } catch (error) { result.cases.push({ id, status: 'FAIL', error: error.message, browserErrors: errors }) }
  finally { await context.close() }
}
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('TC-D16API-24', 1, async (page, field, writes) => {
    for (const raw of ['10.0', '1e1']) {
      await field.fill(raw)
      await Promise.all([page.waitForResponse(r => r.url().endsWith('/scenarios/s') && r.request().method() === 'PUT'), page.getByRole('button', { name: '保存场景', exact: true }).click()])
      assert.equal(writes.at(-1).scenario.steps[1].config.delayMillis, 10)
    }
  })
  await run('TC-D16API-25', 1.5, async (page, field, writes) => {
    for (const raw of ['1.5', '1.0000000000000001', '1e10000', '-1', '600001']) {
      await field.fill(raw); await page.getByRole('button', { name: '保存场景', exact: true }).click()
      await page.locator('.arco-message').filter({ hasText: 'delayMillis' }).first().waitFor(); assert.equal(writes.length, 0); assert.equal(await field.inputValue(), raw)
    }
  })
} finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(c => c.status === 'PASS').length; result.failed = result.cases.length - result.passed
  await fs.writeFile(path.join(out, 'headless-ui.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n'); if (result.failed) process.exitCode = 1
}
