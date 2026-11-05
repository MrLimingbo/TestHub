// Headless Web automation page acceptance uses controlled API fixtures; native HTTP has a separate QA script.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { PlaywrightRunner } from '../../services/playwright-runner/src/runner.js'
import { validateRuntime } from '../../services/playwright-runner/src/protocol.js'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261102/d16-web-fixes-r1'), dist = path.join(root, 'dist')
const stamp = '2026-10-30T00:00:00Z'
const result = { headless: true, isolatedHttpFixtures: true, realBackendAcceptance: false, cases: [], cleanup: false }
const server = http.createServer(async (req, res) => {
  try {
    if (new URL(req.url, 'http://qa.invalid').pathname === '/qa-form') { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end('<input id="input"><div id="result"></div><script>document.querySelector("input").addEventListener("input", event => document.querySelector("#result").textContent = event.target.value)</script>'); return }
    let file = path.resolve(dist, '.' + decodeURIComponent(new URL(req.url, 'http://qa.invalid').pathname))
    if (!file.startsWith(dist + path.sep) || !path.extname(file)) file = path.join(dist, 'index.html')
    const body = await fs.readFile(file)
    res.writeHead(200, { 'Content-Type': { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' }[path.extname(file)] || 'application/octet-stream' })
    res.end(body)
  } catch { if (!res.headersSent) res.writeHead(404); res.end() }
})
await fs.mkdir(out, { recursive: true }); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
let browser
const profile = { id: 'p', name: 'QA Profile', browser: 'chromium', baseUrl: 'https://ui.example.test', viewportWidth: 800, viewportHeight: 600, enabled: true, version: 1 }
const scenario = { id: 's', name: 'QA Scene', browserProfileId: 'p', description: '', failureStrategy: 'STOP', variables: {}, steps: [{ id: 'wait', type: 'WAIT', name: 'Wait', timeoutMillis: 1000 }, { id: 'fill', type: 'FILL', name: 'Fill', selector: '#input', value: '001', timeoutMillis: 1000 }], version: 1 }
async function run(id, kind, operation) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } }), writes = [], errors = []
  await context.route('**/api/**', async route => {
    const r = route.request(), p = new URL(r.url()).pathname; let data
    if (['POST', 'PUT'].includes(r.method())) { const input = r.postDataJSON(); writes.push(input); data = { id: 'saved', ...input } }
    else if (p.endsWith('/auth/me')) data = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp }
    else if (p.endsWith('/auth/csrf')) data = { token: 'fixture', headerName: 'X-CSRF' }
    else if (p.endsWith('/auth/capabilities')) data = { registrationEnabled: true }
    else if (p.endsWith('/access/context')) data = { organizations: [{ id: 'o', name: 'QA', code: 'QA' }], projects: { o: [{ id: 'project', name: 'QA项目', code: 'QA', status: 'ACTIVE', createdAt: stamp, updatedAt: stamp }] } }
    else if (p.endsWith('/capabilities')) data = { systemAdministration: true }
    else if (p.endsWith('/browser-profiles')) data = [profile]
    else if (p.endsWith('/scenarios')) data = [scenario]
    else if (p.endsWith('/runner-status')) data = [{ runnerId: 'r', trusted: true, executionEnabled: true }]
    else data = []
    await route.fulfill({ json: { data } })
  })
  const page = await context.newPage(); page.setDefaultTimeout(10_000); page.on('pageerror', error => errors.push(error.message))
  try {
    await page.goto(origin + '/ui-automation')
    if (kind === 'profile') { await page.locator('.arco-tabs-tab').filter({ hasText: '浏览器项目' }).click(); await page.getByRole('button', { name: '新建浏览器项目', exact: true }).click() }
    else await page.getByRole('button', { name: kind === 'scenario' ? '编辑编排' : '执行场景', exact: true }).click()
    const dialog = page.locator('.arco-modal').filter({ hasText: kind === 'profile' ? '新建浏览器项目' : kind === 'scenario' ? '编辑 Web UI 场景' : '执行 Web UI 场景' })
    if (kind === 'profile') { await dialog.locator('.arco-form-item').filter({ hasText: '配置名称' }).locator('input').fill('QA'); await dialog.locator('.arco-form-item').filter({ hasText: '基准 URL' }).locator('input').fill('https://ui.example.test') }
    const field = name => page.getByLabel(name, { exact: true }).locator('input')
    const save = () => dialog.locator('.arco-modal-footer .arco-btn-primary').click()
    const written = async () => Promise.all([page.waitForResponse(r => ['POST', 'PUT'].includes(r.request().method()) && !r.url().endsWith('/csrf')), save()])
    await operation(page, dialog, field, save, written, writes); assert.deepEqual(errors, []); result.cases.push({ id, status: 'PASS' })
  } catch (error) { result.cases.push({ id, status: 'FAIL', error: error.message, browserErrors: errors }) }
  finally { await context.close() }
}
try {
  browser = await chromium.launch({ headless: true, channel: 'chrome' }); result.browserVersion = browser.version()
  await run('TC-D16WEB-46', 'profile', async (page, dialog, field, save, written, writes) => {
    for (const raw of ['800.5', '800.0000000000000001', '3841']) { await field('Web viewportWidth').fill(raw); await save(); await page.locator('.arco-message').filter({ hasText: 'viewportWidth' }).first().waitFor(); assert.equal(writes.length, 0); assert.equal(await field('Web viewportWidth').inputValue(), raw) }
  })
  await run('TC-D16WEB-47', 'profile', async (page, dialog, field, save, written, writes) => { await field('Web viewportWidth').fill('8e2'); await field('Web viewportHeight').fill('600.0'); await written(); assert.equal(writes.at(-1).viewportWidth, 800); assert.equal(writes.at(-1).viewportHeight, 600) })
  await run('TC-D16WEB-48', 'scenario', async (page, dialog, field, save, written, writes) => {
    await field('Web 步骤 1 timeoutMillis').fill('1000.5'); await save(); await page.locator('.arco-message').filter({ hasText: 'timeoutMillis' }).first().waitFor(); assert.equal(writes.length, 0)
    await field('Web 步骤 1 timeoutMillis').fill('1e3'); await field('Web 步骤 2 timeoutMillis').fill('99'); await save(); assert.equal(writes.length, 0)
    await field('Web 步骤 2 timeoutMillis').fill('100.0'); await written(); assert.equal(writes.at(-1).steps[0].timeoutMillis, 1000); assert.equal(writes.at(-1).steps[1].timeoutMillis, 100)
  })
  await run('TC-D16WEB-49', 'execute', async (page, dialog, field, save, written, writes) => { await field('Web execute timeoutSeconds').fill('300.5'); await save(); await page.locator('.arco-message').filter({ hasText: 'timeoutSeconds' }).first().waitFor(); assert.equal(writes.length, 0); await field('Web execute timeoutSeconds').fill('3e2'); await written(); assert.equal(writes.at(-1).timeoutSeconds, 300) })
  await run('TC-D16WEB-50', 'scenario', async (page, dialog, field, save, written, writes) => {
    await field('Web 步骤 1 timeoutMillis').fill(''); await save(); await page.locator('.arco-message').filter({ hasText: 'timeoutMillis' }).first().waitFor(); assert.equal(writes.length, 0)
    await field('Web 步骤 1 timeoutMillis').fill('50'); await field('Web 步骤 2 timeoutMillis').fill(''); await written(); assert.equal(writes.at(-1).steps[0].timeoutMillis, 50); assert.equal(writes.at(-1).steps[1].timeoutMillis, undefined)
  })
  await run('TC-D16WEB-51', 'scenario', async (page, dialog, field, save, written, writes) => {
    const variables = dialog.locator('.arco-form-item').filter({ hasText: '变量（JSON 对象）' }).locator('textarea'); await variables.fill('{"id":1}'); await save(); await page.locator('.arco-message').filter({ hasText: '字符串' }).first().waitFor(); assert.equal(writes.length, 0)
    await variables.fill('{}'); await field('Web 步骤 1 timeoutMillis').fill('1.5'); await dialog.locator('.step-form').first().locator('header button').click(); await field('Web 步骤 1 timeoutMillis').fill('100.0'); await written(); assert.equal(writes.at(-1).steps[0].timeoutMillis, 100)
  })
  const context = await browser.newContext(); const page = await context.newPage()
  try {
    const task = { taskId: 'fixture-task', executionId: 'fixture-execution' }
    const steps = [{ id: 'nav', type: 'NAVIGATE', name: 'Open', url: '/qa-form', timeoutMillis: null }, { id: 'fill', type: 'FILL', name: 'Fill', selector: '#input', value: '{{id}}', timeoutMillis: 1000 }, { id: 'text', type: 'ASSERT_TEXT', name: 'Assert', selector: '#result', expectedText: '001', timeoutMillis: 1000 }, { id: 'wait', type: 'WAIT', name: 'Wait', timeoutMillis: 1 }]
    const runtime = validateRuntime(task, { contract: 'testhub.playwright-runner.runtime-configuration', version: 1, ...task, browser: { browser: 'chromium', baseUrl: origin, viewportWidth: 800, viewportHeight: 600 }, scenario: { failureStrategy: 'STOP', variables: { id: '001' }, steps } }, [origin])
    const runner = new PlaywrightRunner({ commandClient: {}, readClient: {}, cancelClient: {}, config: { allowedTargetOrigins: [origin] } })
    for (const step of runtime.scenario.steps) await runner.step(page, step, runtime.scenario.variables, origin)
    assert.equal(await page.locator('#input').inputValue(), '001'); result.cases.push({ id: 'TC-D16WEB-55', status: 'PASS', mode: 'real Chrome plus actual Runner.step; not a deployed queue service' })
  } catch (error) { result.cases.push({ id: 'TC-D16WEB-55', status: 'FAIL', error: error.message }) }
  finally { await context.close() }
} finally {
  await browser?.close(); await new Promise(resolve => server.close(resolve)); result.cleanup = true
  result.passed = result.cases.filter(c => c.status === 'PASS').length; result.failed = result.cases.length - result.passed
  await fs.writeFile(path.join(out, 'headless-ui.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n'); if (result.failed) process.exitCode = 1
}
