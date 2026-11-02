// Headless Mock page acceptance uses controlled API fixtures; native HTTP has a separate QA script.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261102/d16-mock-fixes-r1'), dist = path.join(root, 'dist')
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
async function run(id, share, operation) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } }), writes = [], errors = []
  const mockServer = { id: 's', projectId: 'p', name: 'Mock验收', publicKey: 'fixture-public', enabled: true, smartExampleEnabled: false, ruleCount: 0, createdAt: stamp, updatedAt: stamp }
  await context.route('**/api/**', async route => {
    const r = route.request(), p = new URL(r.url()).pathname; let data
    if (r.method() === 'POST') {
      const input = r.postDataJSON(); writes.push({ path: p, input })
      data = p.endsWith('/shares') ? { id: 'share', ...input, shareKey: 'fixture-share', expiresAt: null, revokedAt: null, createdAt: stamp } : { id: 'r', serverId: 's', ...input, enabled: true, createdAt: stamp, updatedAt: stamp }
    }
    else if (p.endsWith('/auth/me')) data = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp }
    else if (p.endsWith('/auth/csrf')) data = { token: 'fixture', headerName: 'X-CSRF' }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA', code: 'QA' }], projects: { o: [{ id: 'p', name: 'QA项目', code: 'QA', status: 'ACTIVE', createdAt: stamp, updatedAt: stamp }] } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else if (p.endsWith('/servers')) data = [mockServer]
    else data = []
    await route.fulfill({ json: { data } })
  })
  const page = await context.newPage(); page.setDefaultTimeout(10_000); page.on('pageerror', error => errors.push(error.message))
  try {
    await page.goto(origin + '/mock')
    await page.getByRole('button', { name: share ? '创建只读分享' : '新建规则', exact: true }).click()
    await page.getByPlaceholder(share ? '例如：合作方订单联调说明' : '例如：预览订单成功').fill('QA')
    const dialog = page.locator('.arco-modal').filter({ has: page.getByPlaceholder(share ? '例如：合作方订单联调说明' : '例如：预览订单成功') })
    await operation(page, dialog, writes); assert.deepEqual(errors, []); result.cases.push({ id, status: 'PASS' })
  } catch (error) { result.cases.push({ id, status: 'FAIL', error: error.message, browserErrors: errors }) }
  finally { await context.close() }
}
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('TC-D16MOCK-35', false, async (page, dialog, writes) => {
    const field = page.getByLabel('Mock 优先级').locator('input')
    for (const raw of ['1.5', '1.0000000000000001', '1001']) {
      await field.fill(raw); await dialog.locator('.arco-modal-footer .arco-btn-primary').click()
      await page.locator('.arco-message').filter({ hasText: 'priority' }).first().waitFor()
      assert.equal(writes.length, 0); assert.equal(await field.inputValue(), raw)
    }
    await field.fill('10')
    const status = page.getByLabel('Mock 响应状态').locator('input')
    for (const raw of ['200.5', '200.0000000000000001', '199', '600']) {
      await status.fill(raw); await dialog.locator('.arco-modal-footer .arco-btn-primary').click()
      await page.locator('.arco-message').filter({ hasText: 'responseStatus' }).first().waitFor()
      assert.equal(writes.length, 0); assert.equal(await status.inputValue(), raw)
    }
  })
  await run('TC-D16MOCK-36', true, async (page, dialog, writes) => {
    const field = page.getByLabel('Mock 有效期（天）').locator('input')
    for (const raw of ['1.5', '1.0000000000000001', '0', '366']) {
      await field.fill(raw); await dialog.locator('.arco-modal-footer .arco-btn-primary').click()
      await page.locator('.arco-message').filter({ hasText: 'expiresInDays' }).first().waitFor()
      assert.equal(writes.length, 0); assert.equal(await field.inputValue(), raw)
    }
  })
  await run('TC-D16MOCK-37', false, async (page, dialog, writes) => {
    await page.getByLabel('Mock 优先级').locator('input').fill('1e1')
    await page.getByLabel('Mock 响应状态').locator('input').fill('200.0')
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/rules') && r.request().method() === 'POST'), dialog.locator('.arco-modal-footer .arco-btn-primary').click()])
    assert.equal(writes.at(-1).input.priority, 10); assert.equal(writes.at(-1).input.responseStatus, 200)
  })
  await run('TC-D16MOCK-40', true, async (page, dialog, writes) => {
    await page.getByLabel('Mock 有效期（天）').locator('input').fill('1.5')
    await page.getByText('永不过期', { exact: true }).click()
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/shares') && r.request().method() === 'POST'), dialog.locator('.arco-modal-footer .arco-btn-primary').click()])
    assert.equal(writes.at(-1).input.expiresInDays, null)
  })
} finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(c => c.status === 'PASS').length; result.failed = result.cases.length - result.passed
  await fs.writeFile(path.join(out, 'headless-ui.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n'); if (result.failed) process.exitCode = 1
}
