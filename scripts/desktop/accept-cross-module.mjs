// Genuine packaged cross-module regression. All windows are hidden before the application entry.
import {Buffer} from 'node:buffer'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import http from 'node:http'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {setTimeout as delay} from 'node:timers/promises'
import {chromium} from '../../services/playwright-runner/node_modules/playwright/index.mjs'
import {offscreenMac} from './offscreen-mac-qa.mjs'
import {performanceAccount} from './qa-performance-account.mjs'
const args=process.argv.slice(2),value=key=>args[args.indexOf(key)+1]
for(const key of ['--app','--output','--browser-cache','--jmeter-cache'])if(!args.includes(key))throw new Error('Required argument: '+key)
const appPath=await fs.realpath(value('--app')),output=path.resolve(value('--output'));await fs.mkdir(path.dirname(output),{recursive:true});await(await fs.open(output,'wx',0o600)).close()
const require=createRequire(import.meta.url),{loadBundle}=require('../../apps/desktop/app/src/local-runtime.cjs'),{writeExecutionOrigins}=require('../../apps/desktop/app/src/local-execution.cjs'),{LocalBrowsers}=require('../../apps/desktop/app/src/local-browsers.cjs')
const resources=path.join(appPath,'Contents/Resources'),bundle=await loadBundle(path.join(resources,'local-runtime/darwin-arm64')),asar=path.join(resources,'app.asar'),hash=async file=>crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex'),originalAsarHash=await hash(asar)
const directory=await fs.mkdtemp(path.resolve('.testhub/cross-module-qa-')),userData=path.join(directory,'desktop'),screenshots=path.join(directory,'screenshots'),label='packaged-arm64',name='TestHub-QA-'+crypto.randomUUID(),run=promisify(execFile)
await fs.mkdir(userData);await fs.mkdir(screenshots);await fs.writeFile(path.join(directory,'.qa-owner.json'),JSON.stringify({name}),{flag:'wx',mode:0o600})
const checks=[],launches=[],hits=[],pass=message=>{checks.push(message);console.log('PASS '+message)}
let nativeApp,accountBrowser,accountService,localOrigin,mockPath,projectId,outcome,failure,forbiddenHits=0,origin
const app={windows:()=>nativeApp?.pages()||[],evaluate:fn=>nativeApp.evaluate(fn),close:async()=>{if(nativeApp){launches.push(await nativeApp.snapshot());await nativeApp.close();nativeApp=undefined}}}
async function pageFor(prefix,excluded=''){for(let n=0;n<900;n++){const page=app.windows().find(p=>p.url().startsWith(prefix)&&(!excluded||!p.url().startsWith(excluded)));if(page)return page;await delay(100)}throw new Error('Owned packaged page unavailable')}
async function launch(){nativeApp=await offscreenMac(appPath,userData,{name,cleanKeychain:false,diagnosticLog:path.join(path.dirname(output),'cross-module-private.log')});localOrigin=new URL(nativeApp.local.url()).origin;return nativeApp.local}
const forbidden=http.createServer((_req,res)=>{forbiddenHits++;res.end('Unauthorized fixture')})
const target=http.createServer(async(req,res)=>{
 hits.push({path:req.url,source:req.headers['x-source']||'browser'})
 if(req.url==='/form'){res.setHeader('content-type','text/html; charset=utf-8');res.end('<!doctype html><meta charset="utf-8"><title>跨模块 QA</title><input id="order"><button id="submit" onclick="globalThis.fetch(\'/order\').then(r=>r.json()).then(r=>document.querySelector(\'#result\').textContent=r.order+\':\'+document.querySelector(\'#order\').value)">提交</button><p id="result"></p>');return}
 try{assert.ok(localOrigin&&mockPath);const r=await globalThis.fetch(localOrigin+mockPath);res.writeHead(r.status,{'content-type':'application/json'});res.end(await r.text())}catch{res.writeHead(502);res.end('{"error":"Owned QA gateway failure"}')}
})
let forbiddenOrigin
async function read(page, route) { return page.evaluate(async route => {
  const response = await globalThis.fetch(route), value = await response.json()
  if (!response.ok) throw Error(value.message || 'Read failed'); return value.data
}, route) }
async function mutate(page, route, body, method = 'POST') { return page.evaluate(async ({ route, body, method }) => {
  const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data
  const response = await globalThis.fetch(route, { method, headers: { 'content-type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(body) })
  const value = await response.json(); if (!response.ok) throw Error(value.message || 'Mutation failed'); return value.data
}, { route, body, method }) }
const base = () => `/api/v1/projects/${projectId}`
async function waitStatus(page, module, id, expected, seconds = 120) {
  let value
  for (let n = 0; n < seconds * 5; n++) {
    if (module === 'ui-automation') value = (await read(page, base() + `/${module}/executions/${id}/report`)).execution
    else value = await read(page, base() + `/${module}/executions/${id}`)
    if (expected.includes(value.status)) return value
    if (['SUCCEEDED','FAILED','CANCELLED','REJECTED','TIMED_OUT'].includes(value.status)) throw Error('Unexpected terminal state ' + value.status)
    await delay(200)
  }
  throw Error('Execution timeout ' + JSON.stringify(value))
}
async function functional(page, project, prefix) {
  const root = `/api/v1/projects/${project}/functional`
  const catalog = await mutate(page, root + '/catalogs', { name: prefix + '用例', scope: 'CASES', sortOrder: 0 })
  const planCatalog = await mutate(page, root + '/catalogs', { name: prefix + '计划', scope: 'PLANS', sortOrder: 0 })
  const cases = []
  for (const outcome of ['PASSED','FAILED']) cases.push(await mutate(page, root + '/cases', { catalogId: catalog.id, caseCode: prefix + '_' + outcome, title: outcome, priority: 'P1', status: 'READY', caseType: 'FUNCTIONAL', precondition: 'QA', steps: '核对', expectedResult: '符合预期' }))
  const plan = await mutate(page, root + '/plans', { catalogId: planCatalog.id, planCode: prefix + '_PLAN', name: prefix + '功能计划', status: 'READY', description: 'Isolated QA', caseIds: cases.map(c => c.id) })
  const run = await mutate(page, root + `/plans/${plan.id}/runs`, { name: prefix + '功能批次' })
  for (const item of await read(page, root + `/runs/${run.id}/cases`)) {
    const outcome = item.caseCode.endsWith('_PASSED') ? 'PASSED' : 'FAILED'
    for (const step of await read(page, root + `/run-cases/${item.id}/steps`)) await mutate(page, root + `/run-steps/${step.id}`, { status: outcome, actualResult: 'QA recorded result' }, 'PUT')
    await mutate(page, root + `/run-cases/${item.id}`, { status: outcome, note: 'QA recorded result' }, 'PUT')
  }
  return run
}
async function apiScenario(page, name, failed = false, slow = false) {
  return mutate(page, base() + '/api-automation/scenarios', { name, environmentCode: 'test', failureStrategy: 'STOP', variables: {}, steps: [
    ...(slow ? [{ id: 'wait', type: 'WAIT', name: '取消等待', config: { delayMillis: 20000 } }] : []),
    { id: 'request', type: 'REQUEST', name: '读取真实 Mock', config: { method: 'GET', url: origin + (slow ? '/must-not-call' : '/order') } },
    { id: 'assert', type: 'ASSERT', name: '状态断言', config: { source: 'LAST_STATUS_CODE', expectedStatus: failed ? 201 : 200 } },
  ] })
}
async function uiScenario(page, name, failed = false, slow = false) {
  const root = base() + '/ui-automation'
  const profile = await mutate(page, root + '/browser-profiles', { name, browser: 'chromium', baseUrl: origin, viewportWidth: 800, viewportHeight: 600, enabled: true })
  return mutate(page, root + '/scenarios', { browserProfileId: profile.id, name, failureStrategy: 'STOP', variables: { value: 'QA-VALUE' }, steps: [
    { id: 'navigate', type: 'NAVIGATE', name: '打开真实目标', url: '/form', timeoutMillis: 10000 },
    { id: 'fill', type: 'FILL', name: '填写', selector: '#order', value: '${value}', timeoutMillis: 10000 },
    { id: 'click', type: 'CLICK', name: '读取 Mock', selector: '#submit', timeoutMillis: 10000 },
    { id: 'assert', type: 'ASSERT_TEXT', name: '结果断言', selector: '#result', expectedText: failed ? 'must-fail' : 'QA-ORDER:${value}', timeoutMillis: failed ? 500 : 10000 },
    { id: 'capture', type: 'SCREENSHOT', name: '截图', timeoutMillis: 10000 },
    { id: 'wait', type: 'WAIT', name: '取消等待', timeoutMillis: slow ? 60000 : 20 },
  ] })
}
async function performancePlan(page, name, durationSeconds = 10) {
  return mutate(page, base() + '/performance/plans', { name, description: 'Owned loopback only', status: 'READY', threadGroups: [{
    name: '统一压测', targetUrl: origin + '/order', loadModel: 'STEADY', threadCount: 2, rampUpSeconds: 0,
    durationSeconds, targetTps: 3, requestMethod: 'GET', requestHeaders: { 'X-Source': 'jmeter' },
    expectedStatusCode: 200, responseContains: 'QA-ORDER', errorThresholdPercent: 0,
  }] })
}
async function screenshot(page, name) { await page.screenshot({ path: path.join(screenshots, `${label}-${name}.png`), fullPage: true }) }
try {
  await new Promise(resolve=>forbidden.listen(0,'127.0.0.1',resolve));forbiddenOrigin='http://127.0.0.1:'+forbidden.address().port
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+target.address().port
  await writeExecutionOrigins(userData,[origin]);await fs.mkdir(path.join(userData,'engines'));await fs.cp(await fs.realpath(value('--jmeter-cache')),path.join(userData,'engines/apache-jmeter-5.6.3'),{recursive:true,verbatimSymlinks:true})
  const manager=new LocalBrowsers({directory:path.join(userData,'browser-engines'),bundle}),plan=await manager.plan('chromium'),sourceCache=await fs.realpath(value('--browser-cache'));await fs.mkdir(plan.cache,{recursive:true});for(const entry of [...plan.components.map(c=>c.directory),'.ready-chromium.json'])await fs.cp(path.join(sourceCache,entry),path.join(plan.cache,entry),{recursive:true,verbatimSymlinks:true});assert.equal(await manager.installed('chromium'),plan.cache)
  let local = await launch()
  const owner = (await read(local, '/api/v1/auth/me')).id, context = await read(local, '/api/v1/access/context')
  projectId = context.projects[context.organizations[0].id][0].id
  const unified = () => base() + '/tasks/executions'
  assert.deepEqual(await read(local, unified()), [])
  const manual = await mutate(local, base() + '/tasks', { taskType: 'API', name: '独立手工登记' })
  assert.deepEqual(await read(local, unified()), []); pass('fresh local owner reads an empty unified list and manual registration does not fabricate execution')
  const run = await functional(local, projectId, 'LOCAL')
  const recorded = (await read(local, unified()))[0]
  assert.equal(recorded.id, run.id); assert.equal(recorded.status, 'COMPLETED'); assert.equal(recorded.passedCount, 1); assert.equal(recorded.failedCount, 1)
  pass('real functional batch with one pass and one failure appears as COMPLETED with original counts')
  const mock = await mutate(local, base() + '/mock/servers', { name: '跨模块 Mock' })
  await mutate(local, base() + `/mock/servers/${mock.id}/rules`, { name: '订单响应', httpMethod: 'GET', path: '/order', priority: 10, requestBodyMatch: 'ANY', responseStatus: 200, responseHeaders: { 'Content-Type': 'application/json' }, responseBody: '{"ok":true,"order":"QA-ORDER"}' })
  mockPath = `/api/v1/mock/${mock.publicKey}/order`
  assert.equal((await globalThis.fetch(localOrigin + mockPath)).status, 200); assert.equal((await globalThis.fetch(origin + '/order').then(r => r.json())).order, 'QA-ORDER')
  pass('owned gateway forwards to the same workspace real Mock rule')
  await assert.rejects(local.evaluate(target => globalThis.testhubDesktop.ensureJmeter([target]), forbiddenOrigin), /本地执行设置/)
  await assert.rejects(local.evaluate(origin => globalThis.testhubDesktop.ensureBrowser({ browser: 'chromium', origin }), forbiddenOrigin), /本地执行设置/)
  assert.equal(forbiddenHits, 0); pass('unapproved origins cannot prepare performance or browser execution')
  await app.evaluate(({ session }) => session.defaultSession.webRequest.onBeforeRequest({ urls: ['https://downloads.apache.org/*','https://cdn.playwright.dev/*'] }, (_details, callback) => callback({ cancel: true })))
  await local.evaluate(origin => globalThis.testhubDesktop.ensureJmeter([origin]), origin)
  await local.evaluate(origin => globalThis.testhubDesktop.ensureBrowser({ browser: 'chromium', origin }), origin)
  pass('bundled Java and verified cached JMeter/Chromium prepare through native bridges')
  const api = await apiScenario(local, '统一接口成功'), ui = await uiScenario(local, '统一浏览器成功'), performance = await performancePlan(local, '统一性能成功')
  const apiExecution = await mutate(local, base() + `/api-automation/scenarios/${api.id}/executions`, { timeoutSeconds: 60 })
  const uiExecution = await mutate(local, base() + `/ui-automation/scenarios/${ui.id}/executions`, { timeoutSeconds: 60 })
  const performanceExecution = await mutate(local, base() + `/performance/plans/${performance.id}/executions`, { timeoutSeconds: 120 })
  const completed = await Promise.all([waitStatus(local,'api-automation',apiExecution.id,['SUCCEEDED']),waitStatus(local,'ui-automation',uiExecution.id,['SUCCEEDED']),waitStatus(local,'performance',performanceExecution.id,['SUCCEEDED'])])
  assert.ok(completed.every(r => r.finishedAt)); assert.ok(hits.some(h => h.source === 'jmeter')); assert.ok(hits.some(h => h.path === '/form'))
  pass('API, JMeter and Chromium executions complete in one workspace against the same real Mock contract')
  const apiReport = await read(local, base() + `/api-automation/executions/${apiExecution.id}/report`)
  const apiIntegrity = await read(local, base() + `/api-automation/executions/${apiExecution.id}/report/integrity`)
  const bytes = await local.evaluate(async route => Array.from(new Uint8Array(await (await globalThis.fetch(route)).arrayBuffer())), base() + `/api-automation/executions/${apiExecution.id}/report/export`)
  assert.equal(crypto.createHash('sha256').update(Buffer.from(bytes)).digest('hex'), apiIntegrity.sha256); assert.equal(apiReport.execution.id, apiExecution.id)
  const perfReport = await read(local, base() + `/performance/executions/${performanceExecution.id}/report`)
  assert.ok(perfReport.summary.sampleCount > 10); assert.equal(perfReport.quality.status, 'PASSED')
  const uiReport = await read(local, base() + `/ui-automation/executions/${uiExecution.id}/report`)
  assert.equal(uiReport.summary.passedSteps, 6); assert.ok(uiReport.artifacts.some(a => a.artifactType === 'TRACE'))
  for (const [module, execution, artifacts] of [['performance', performanceExecution, perfReport.artifacts], ['ui-automation', uiExecution, uiReport.artifacts]]) {
    for (const artifact of artifacts) {
      const data = await local.evaluate(async route => Array.from(new Uint8Array(await (await globalThis.fetch(route)).arrayBuffer())), base() + `/${module}/executions/${execution.id}/artifacts/${artifact.id}/download`)
      assert.equal(data.length, artifact.sizeBytes); assert.equal(crypto.createHash('sha256').update(Buffer.from(data)).digest('hex'), artifact.checksum)
    }
  }
  pass('execution reports and actual API export, JTL/log, screenshot/video/Trace downloads match integrity metadata')
  const badApi = await apiScenario(local, '统一接口失败', true), badUi = await uiScenario(local, '统一浏览器失败', true)
  const failedApi = await mutate(local, base() + `/api-automation/scenarios/${badApi.id}/executions`, { timeoutSeconds: 60 })
  const failedUi = await mutate(local, base() + `/ui-automation/scenarios/${badUi.id}/executions`, { timeoutSeconds: 60 })
  await waitStatus(local,'api-automation',failedApi.id,['FAILED']); await waitStatus(local,'ui-automation',failedUi.id,['FAILED'])
  let records = await read(local, unified()); assert.equal(records.length, 6); assert.equal(records.find(r => r.id === failedApi.id).status, 'FAILED'); assert.equal(records.find(r => r.id === failedUi.id).status, 'FAILED')
  pass('real API and browser assertion failures keep their native failure states in the unified projection')
  const slowApi = await apiScenario(local, '统一接口取消', false, true), slowUi = await uiScenario(local, '统一浏览器取消', false, true), slowPerf = await performancePlan(local, '统一性能取消', 120)
  const cancellingApi = await mutate(local, base() + `/api-automation/scenarios/${slowApi.id}/executions`, { timeoutSeconds: 90 })
  const cancellingUi = await mutate(local, base() + `/ui-automation/scenarios/${slowUi.id}/executions`, { timeoutSeconds: 120 })
  const cancellingPerf = await mutate(local, base() + `/performance/plans/${slowPerf.id}/executions`, { timeoutSeconds: 180 })
  await Promise.all([waitStatus(local,'api-automation',cancellingApi.id,['RUNNING']),waitStatus(local,'ui-automation',cancellingUi.id,['RUNNING']),waitStatus(local,'performance',cancellingPerf.id,['RUNNING'])])
  await local.goto(localOrigin + '/tasks'); await local.getByRole('heading', { name: '真实执行记录', exact: true }).waitFor()
  const panel = local.getByRole('region', { name: '真实执行记录' })
  await panel.getByText('统一接口取消', { exact: true }).waitFor(); assert.ok((await panel.innerText()).includes('执行中'))
  for (const [module, item, method, suffix] of [['api-automation', cancellingApi, 'DELETE', ''], ['ui-automation', cancellingUi, 'POST', '/cancel'], ['performance', cancellingPerf, 'DELETE', '']]) await mutate(local, base() + `/${module}/executions/${item.id}${suffix}`, {}, method)
  await Promise.all([waitStatus(local,'api-automation',cancellingApi.id,['CANCELLED']),waitStatus(local,'ui-automation',cancellingUi.id,['CANCELLED']),waitStatus(local,'performance',cancellingPerf.id,['CANCELLED'])])
  await delay(1000); const requestCount = hits.length; await delay(1500); assert.equal(hits.length, requestCount); assert.ok(!hits.some(h => h.path === '/must-not-call'))
  await panel.getByText('当前范围 0 条未结束', { exact: false }).waitFor({ timeout: 15000 })
  pass('source-module cancellation stops all three real runners and the visible unified list refreshes terminal states without overlap')
  records = await read(local, unified()); assert.equal(records.length, 9); assert.equal(records.filter(r => r.status === 'CANCELLED').length, 3); assert.ok(records.every(r => r.id !== manual.id))
  await local.getByRole('region', { name: '真实执行记录' }).getByPlaceholder('搜索执行名称').fill('统一接口')
  await panel.getByText('统一性能成功', { exact: true }).waitFor({ state: 'hidden' })
  assert.equal(await panel.getByRole('row').count(), 4)
  await panel.getByPlaceholder('搜索执行名称').fill('')
  await panel.getByLabel('筛选执行模块').click(); await local.locator('.arco-select-option:visible').getByText('性能测试', { exact: true }).click()
  await panel.getByText('统一性能成功', { exact: true }).waitFor(); assert.equal(await panel.getByRole('row').count(), 3)
  await panel.getByLabel('筛选执行状态').click(); await local.locator('.arco-select-option:visible').getByText('已取消', { exact: true }).click()
  assert.equal(await panel.getByRole('row').count(), 2)
  pass('real desktop filters module, native cancellation state and execution name within the bounded recent range')
  await panel.getByLabel('筛选执行状态').click(); await local.locator('.arco-select-option:visible').getByText('全部状态', { exact: true }).click()
  await panel.getByRole('row').filter({ hasText: '统一性能成功' }).getByRole('link', { name: '前往模块' }).click()
  await local.getByRole('button', { name: '提交压测', exact: true }).first().waitFor(); assert.equal(new URL(local.url()).pathname, '/performance')
  pass('module detail navigation keeps project selection and existing performance report access')
  const report = await read(local, '/api/v1/reports/quality')
  assert.equal(report.summary.completedExecutions, 5); assert.equal(report.summary.passedExecutions, 3); assert.equal(report.summary.failedExecutions, 2); assert.equal(report.summary.passRatePercent, 60)
  pass('quality statistics retain existing functional/API/performance outcome scope; browser and cancelled executions are not added silently')
  await mutate(local, base() + '/tasks/' + manual.id, { name: manual.name, status: 'SUCCEEDED', progress: 100, expectedVersion: 1 }, 'PUT')
  assert.deepEqual(await read(local, unified()), records); assert.deepEqual((await read(local, '/api/v1/reports/quality')).summary, report.summary)
  pass('editing a manual registration does not alter real execution records or quality totals')
  await mutate(local, base() + '/notification-templates', { name: '迁移前停用通知', eventCode: 'quality.report.ready', channel: 'EMAIL', subjectTemplate: '{{project}} {{status}}', bodyTemplate: '{{event}} {{task}}', enabled: false })
  const schedule = await mutate(local, '/api/v1/reports/quality/schedules', { name: '跨模块汇总', projectIds: [projectId], cronExpression: '0 0 0 1 1 ? 2099', timezone: 'Asia/Shanghai', periodDays: 7, enabled: true })
  const generated = await mutate(local, `/api/v1/reports/quality/schedules/${schedule.id}/run`, {})
  assert.equal(generated.status, 'SUCCEEDED'); assert.equal(generated.deliveryCount, 0); assert.deepEqual(await read(local, base() + '/notification-deliveries'), [])
  pass('scheduled report generation uses actual cross-module data and leaves disabled notifications undelivered')
  const other = await mutate(local, '/api/v1/projects', { name: '隔离空项目', code: 'QA_UNIFIED_OTHER', description: 'Owned isolated project' })
  assert.deepEqual(await read(local, `/api/v1/projects/${other.id}/tasks/executions`), [])
  await local.goto(localOrigin + '/tasks'); await panel.getByText('统一接口成功', { exact: true }).waitFor()
  await local.locator('.task-summary .arco-select').click(); await local.locator('.arco-select-option:visible').getByText('隔离空项目', { exact: true }).click()
  await panel.getByText('当前筛选范围没有真实执行记录。', { exact: true }).waitFor(); assert.equal(await panel.getByText('统一接口成功', { exact: true }).count(), 0)
  await local.locator('.task-summary .arco-select').click(); await local.locator('.arco-select-option:visible').getByText('我的项目', { exact: true }).click()
  await panel.getByText('统一接口成功', { exact: true }).waitFor()
  const width = await panel.evaluate(node => ({ width: node.clientWidth, content: node.scrollWidth }))
  assert.ok(width.content <= width.width + 1, 'Execution panel must contain horizontal table scrolling')
  await screenshot(local, 'tasks')
  await panel.getByText('LOCAL功能批次', { exact: true }).scrollIntoViewIfNeeded(); await screenshot(local, 'tasks-bottom')
  pass('project switch clears execution rows and filters and restores only the selected project records')
  await app.close(); local = await launch()
  assert.equal((await read(local, '/api/v1/auth/me')).id, owner); assert.deepEqual(await read(local, unified()), records)
  assert.deepEqual((await read(local, '/api/v1/reports/quality')).summary, report.summary)
  assert.equal((await read(local, base() + '/tasks'))[0].status, 'SUCCEEDED')
  assert.equal((await read(local, base() + `/performance/executions/${performanceExecution.id}/report`)).summary.sampleCount, perfReport.summary.sampleCount)
  pass('restart preserves owner, manual registration, all real execution snapshots, reports and quality counts')
  accountService = await performanceAccount({bundle,directory:path.join(directory,'account-service'),origin});const server=accountService.server
  await local.evaluate(() => globalThis.testhubDesktop.connectAccount()); const setup = await pageFor('testhub-desktop://setup/setup.html')
  await setup.locator('#server-url').fill(server); await setup.locator('#connect').click(); const remote = await pageFor(server)
  const account = 'unified' + Date.now(), password = crypto.randomBytes(18).toString('base64url')
  await mutate(remote,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'统一执行账号',password}); await remote.goto(server+'/tasks')
  const rc = await read(remote,'/api/v1/access/context'), targetProject = rc.projects[rc.organizations[0].id][0].id
  const targetRoute = `/api/v1/projects/${targetProject}/tasks/executions`
  assert.deepEqual(await read(remote,targetRoute),[]); pass('registered account gets an independent empty project; local execution history is not automatically uploaded')
  const accountRun = await functional(remote,targetProject,'ACCOUNT')
  const browserPlan = await new (require('../../apps/desktop/app/src/local-browsers.cjs').LocalBrowsers)({directory:path.join(userData,'browser-engines'),bundle}).plan('chromium'); const shell = browserPlan.components.find(item=>item.name==='chromium-headless-shell'); accountBrowser = await chromium.launch({headless:true,executablePath:path.join(browserPlan.cache,shell.directory,shell.executable)}); const web = await accountBrowser.newPage({viewport:{width:1440,height:1000}})
  await web.goto(server+'/login'); await mutate(web,'/api/v1/auth/login',{account,password}); await web.goto(server+'/tasks')
  await web.getByRole('region',{name:'真实执行记录'}).getByText('ACCOUNT功能批次',{exact:true}).waitFor()
  assert.deepEqual(await read(web,targetRoute),await read(remote,targetRoute)); assert.equal((await read(web,targetRoute))[0].id,accountRun.id); await screenshot(web,'account-tasks')
  pass('same registered account sees its actual functional execution in both independent web browser and desktop account workspace')
  const outsider = await accountBrowser.newPage(); await outsider.goto(server+'/login')
  await mutate(outsider,'/api/v1/auth/register',{account:account+'other',email:account+'other@example.test',displayName:'隔离账号',password:crypto.randomBytes(18).toString('base64url')})
  assert.equal(await outsider.evaluate(async route => (await globalThis.fetch(route)).status,targetRoute),403)
  pass('another registered organization cannot read the unified execution endpoint')
  await app.evaluate(({Menu}) => Menu.getApplicationMenu().items[0].submenu.items.find(item=>item.label==='本地工作区').click())
  local = await pageFor('http://127.0.0.1:',server); await local.getByText('本地工作区',{exact:true}).first().waitFor(); localOrigin = new URL(local.url()).origin
  assert.deepEqual(await read(local,unified()),records); assert.equal(forbiddenHits,0)
  await app.close() // Verify and record the final hidden window state before approving this run.
  const result = {passed:true,checks,realExecutionCount:records.length,summary:report.summary,forbiddenHits,requestCount:hits.length,isPackaged:true,asarUnchanged:crypto.createHash('sha256').update(await fs.readFile(asar)).digest('hex')===originalAsarHash,backgroundWindows:launches,
    scope:{mobileDeviceExecuted:false,downloadsFirstUseVerified:false,notificationTransportExecuted:false,qualityReportScope:['FUNCTIONAL','API','PERFORMANCE']}}
  outcome=result;pass('returning local retains original execution history and unauthorized target received zero requests')
  result.checks=checks
}catch(error){failure=error;outcome={passed:false,checks,error:error.message,backgroundWindows:launches}}
finally{
 let cleanupError
 for(const stop of [()=>accountBrowser?.close(),()=>app.close(),()=>accountService?.stop()])try{await stop()}catch(e){cleanupError||=e}
 for(const server of [target,forbidden]){server.closeAllConnections();if(server.listening)await new Promise(resolve=>server.close(resolve))}
 try{await run('/usr/bin/security',['delete-generic-password','-s',name+' Safe Storage','-a',name+' Key'])}catch(e){if(e.code!==44)cleanupError||=e}
 try{await run('/usr/bin/security',['find-generic-password','-s',name+' Safe Storage']);cleanupError||=new Error('Owned QA key remains')}catch(e){if(e.code!==44)cleanupError||=e}
 if(!cleanupError)await fs.rm(directory,{recursive:true,force:true})
 outcome.ownedDataAndProcessesRemoved=!cleanupError;outcome.ownedQaKeychainAbsent=!cleanupError
 if(cleanupError){outcome.passed=false;failure||=cleanupError}
 await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
console.log('PASS packaged cross-module regression '+checks.length+' checks')
