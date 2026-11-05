'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { Buffer } = require('node:buffer')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { ReadOperations, requestSignal } = require('../app/src/read-operation.cjs')
const { syncBinary } = require('../app/src/project-sync-ipc.cjs')
const { jsonRequest } = require('../app/src/functional-transfer.cjs')
const turn = () => new Promise(resolve => globalThis.setImmediate(resolve))
function deferred() { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function harness() {
  const f = windowFixture(); let clock = 0
  const h = { ...f, locked: false, plan: undefined }
  h.manager = new ReadOperations({ trusted: e => e.sender === f.contents && e.senderFrame.url.startsWith('testhub-desktop://setup/'), now: () => clock })
  h.exclusive = async fn => { if (h.locked) throw new Error('busy'); h.locked = true; try { return await fn() } finally { h.locked = false } }
  h.run = fn => h.manager.run(h.event, h.exclusive, fn, () => { h.plan = undefined })
  h.cancel = () => h.manager.cancel(h.event, h.manager.status(h.event).id)
  h.advance = value => { clock += value }
  return h
}
const { installProjectSync, syncRequest } = require('../app/src/project-sync-ipc.cjs')
function windowFixture(page = 'task-sync.html') {
  const contents = new EventEmitter(); contents.id = 1; contents.mainFrame = { url: 'testhub-desktop://setup/' + page }
  contents.isDestroyed = () => false
  return { contents, event: { sender: contents, senderFrame: contents.mainFrame } }
}
test('N10 JSON request preserves caller cancellation through fetch', async () => {
  const abort = new globalThis.AbortController(); let signal, finish
  const pending = syncRequest((_url, init) => { signal = init.signal; return new Promise(resolve => { finish = resolve }) }, 'https://example.test', { signal: abort.signal })
  abort.abort()
  try { assert.equal(signal.aborted, true) }
  finally { finish(new globalThis.Response(JSON.stringify({ data: {} }))); await pending.catch(() => {}) }
  let stopped = 0; const body = new globalThis.ReadableStream({ start(c) { c.enqueue(Buffer.from('{"data":')); }, cancel() { stopped++ } })
  const second = new globalThis.AbortController()
  const reading = syncRequest(async () => new globalThis.Response(body), 'https://example.test', { signal: second.signal })
  await turn(); second.abort(); await assert.rejects(reading)
  assert.equal(stopped, 1); assert.equal(body.locked, false)
})
test('N13 project sync context uses the shared read lifecycle', async () => {
  const { contents, event } = windowFixture(); const handlers = new Map(); let reads = 0
  class Client {
    async load() { this.state = { binding: null } }
    async status() { return this.state }
    async local() { return { organizations: [], projects: {} } }
    async remote(route) { return route.endsWith('/me') ? { id: 'qa' } : this.local() }
  }
  installProjectSync({ ipcMain: { handle: (name, fn) => handlers.set(name, fn) }, directory: '/unused', origin: 'testhub-desktop://setup', windows: () => ({ setup: { webContents: contents } }), runtime: async () => ({ codec: {}, url: 'http://127.0.0.1:1234' }), server: async () => 'https://example.test', exclusive: fn => fn(), readOperations: { run: async (_event, exclusive, fn) => { reads++; return exclusive(fn) } } }, { Client, Store: class {}, channel: 'task-sync', page: 'task-sync.html', title: '任务同步' })
  await handlers.get('testhub-desktop:task-sync:context')(event)
  assert.equal(reads, 1)
  await syncMatrix()
})
test('N01 context cancellation waits for fetch termination and releases its lock', async () => {
  const h = harness(); let signal, writes = 0
  const pending = h.run(() => syncRequest((_url, init) => { signal = init.signal; writes += init.method === 'POST'; return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true })) }, 'https://example.test'))
  await turn(); h.cancel(); await assert.rejects(pending, { code: 'READ_CANCELLED' })
  assert.equal(signal.aborted, true); assert.equal(h.locked, false); assert.equal(writes, 0); assert.equal(h.manager.status(h.event).state, 'CANCELLED')
  const loading = deferred(), handlers = new Map(); let starts = 0
  class Client {
    async load() { await loading.promise; this.state = { binding: null } }
    async status() { return this.state }
  }
  installProjectSync({ ipcMain: { handle: (key, fn) => handlers.set(key, fn) }, windows: () => ({ setup: { webContents: h.contents } }), origin: 'testhub-desktop://setup', directory: '/unused', statusRuntime: async () => ({ codec: {} }), runtime: async () => { starts++; throw new Error('must not start') }, exclusive: h.exclusive, readOperations: h.manager }, { Client, Store: class {}, channel: 'task-sync', page: 'task-sync.html', title: '任务同步' })
  const cold = handlers.get('testhub-desktop:task-sync:status')(h.event); await turn(); h.cancel(); loading.resolve()
  await assert.rejects(cold, { code: 'READ_CANCELLED' })
  assert.equal((await handlers.get('testhub-desktop:task-sync:status')(h.event)).binding, null)
  assert.equal(h.manager.status(h.event).state, 'CANCELLED'); assert.equal(starts, 0)
})
test('N02 preview cancellation invalidates its plan immediately and on termination', async () => {
  const h = harness(), wait = deferred(); const pending = h.run(async () => { h.plan = { token: 'old' }; await wait.promise })
  await turn(); h.cancel(); assert.equal(h.plan, undefined); assert.equal(h.locked, true)
  wait.resolve(); await assert.rejects(pending, { code: 'READ_CANCELLED' }); assert.equal(h.locked, false)
})
test('N03 uncooperative work remains locked after the five second target', async () => {
  const h = harness(), wait = deferred(); const pending = h.run(() => wait.promise)
  await turn(); h.cancel(); h.advance(5001)
  assert.equal(h.manager.status(h.event).state, 'WAITING'); assert.equal(h.locked, true)
  await assert.rejects(h.exclusive(() => {}), /busy/)
  wait.resolve(); await assert.rejects(pending, { code: 'READ_CANCELLED' }); assert.equal(h.locked, false)
})
test('N04 a late plan is discarded and a later preview stays independent', async () => {
  const h = harness(), wait = deferred(); const pending = h.run(async () => { await wait.promise; h.plan = { token: 'late' }; return h.plan })
  await turn(); h.cancel(); wait.resolve(); await assert.rejects(pending, { code: 'READ_CANCELLED' }); assert.equal(h.plan, undefined)
  await h.run(async () => { h.plan = { token: 'fresh' }; return h.plan }); assert.equal(h.plan.token, 'fresh')
})
test('N05 success and cancellation publish exactly one terminal outcome', async () => {
  const h = harness(); assert.equal(await h.run(async () => 'ok'), 'ok'); const completed = h.manager.status(h.event)
  assert.equal(h.manager.cancel(h.event, completed.id).state, 'COMPLETED')
  const wait = deferred(), pending = h.run(() => wait.promise); await turn(); h.cancel(); wait.resolve('late')
  await assert.rejects(pending, { code: 'READ_CANCELLED' }); assert.equal(h.manager.status(h.event).state, 'CANCELLED')
})
test('N06 repeated cancellation is idempotent and cannot cancel the next operation', async () => {
  const h = harness(), wait = deferred(), pending = h.run(() => wait.promise); await turn(); const id = h.cancel().id
  assert.equal(h.manager.cancel(h.event, id).state, 'CANCELLING'); wait.resolve(); await assert.rejects(pending)
  assert.equal(h.manager.cancel(h.event, id).state, 'CANCELLED')
  const next = deferred(), p = h.run(() => next.promise); await turn(); assert.throws(() => h.manager.cancel(h.event, id), /失效/)
  assert.equal(h.manager.status(h.event).state, 'RUNNING'); next.resolve(); await p
})
test('N07 other senders frames pages and identifiers cannot cancel the owner', async () => {
  const h = harness(), wait = deferred(), pending = h.run(() => wait.promise); await turn(); const id = h.manager.status(h.event).id
  assert.throws(() => h.manager.cancel(windowFixture().event, id), /不受信任/)
  assert.throws(() => h.manager.cancel({ sender: h.contents, senderFrame: { url: h.contents.mainFrame.url } }, id), /不受信任/)
  h.contents.mainFrame.url = 'testhub-desktop://setup/other.html'; assert.throws(() => h.manager.cancel(h.event, id), /失效/)
  h.contents.mainFrame.url = 'testhub-desktop://setup/task-sync.html'; assert.throws(() => h.manager.cancel(h.event, 'old-id'), /失效/)
  assert.equal(h.locked, true); wait.resolve(); await pending
})
test('N08 successful preview retains its token and normal request result', async () => {
  const h = harness(); const result = await h.run(async () => { const data = await jsonRequest(async () => new globalThis.Response('{"data":{"count":1}}'), 'https://example.test'); h.plan = { token: 'valid' }; return data })
  assert.equal(result.count, 1); assert.equal(h.plan.token, 'valid'); assert.equal(h.manager.status(h.event).state, 'COMPLETED'); assert.equal(h.locked, false)
})
test('N09 network errors and timeouts clean up before another read', async () => {
  const h = harness()
  await assert.rejects(h.run(() => syncRequest(async () => { throw new Error('private detail') }, 'https://example.test')), /暂时不可达/)
  const timer = globalThis.setTimeout(() => {}, 100)
  try { await assert.rejects(h.run(() => syncRequest((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason))), 'https://example.test', {}, { timeoutMs: 15 })), /暂时不可达/) } finally { globalThis.clearTimeout(timer) }
  assert.equal(h.manager.status(h.event).state, 'FAILED'); assert.equal(h.locked, false); assert.equal(await h.run(async () => 2), 2)
})
test('N11 binary cancellation closes the reader and digest validation is retained', async () => {
  let stopped = 0; const body = new globalThis.ReadableStream({ start(c) { c.enqueue(Buffer.from('a')) }, cancel() { stopped++ } })
  const abort = new globalThis.AbortController(); const pending = syncBinary(async () => new globalThis.Response(body), 'https://example.test', 2, '0'.repeat(64), { signal: abort.signal })
  await turn(); abort.abort(); await assert.rejects(pending); assert.equal(stopped, 1); assert.equal(body.locked, false)
  await assert.rejects(syncBinary(async () => new globalThis.Response('ab'), 'https://example.test', 2, '0'.repeat(64)), { code: 'SYNC_BINARY_INTEGRITY' })
})
test('N12 writes keep their original exclusive flow and do not accept read cancellation', async () => {
  const h = harness(), wait = deferred(), write = h.exclusive(() => wait.promise)
  assert.equal(h.manager.status(h.event).state, 'IDLE'); assert.throws(() => h.manager.cancel(h.event, 'write'), /失效/); assert.equal(h.locked, true)
  wait.resolve(); await write
  let writes = 0; await assert.rejects(h.run(() => jsonRequest(async () => { writes++; }, 'https://example.test', { method: 'POST' })), /只读预览/); assert.equal(writes, 0)
  const handlers = new Map(); let starts = 0
  class Client {
    async load() { this.state = { binding: { enabled: true } } }
    async status() { return this.state }
    async enabled(value) { this.state.binding.enabled = value }
  }
  installProjectSync({ ipcMain: { handle: (key, fn) => handlers.set(key, fn) }, windows: () => ({ setup: { webContents: h.contents } }), origin: 'testhub-desktop://setup', directory: '/unused', statusRuntime: async () => ({ codec: {} }), runtime: async () => { starts++; throw new Error('offline') }, exclusive: h.exclusive, readOperations: h.manager }, { Client, Store: class {}, channel: 'task-sync', page: 'task-sync.html', title: '任务同步' })
  await handlers.get('testhub-desktop:task-sync:status')(h.event)
  assert.equal((await handlers.get('testhub-desktop:task-sync:enabled')(h.event, false)).binding.enabled, false)
  assert.equal(starts, 0)
})
test('N14 every native transfer context and prepare uses cancellation and discards late tokens', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../app/src/main.cjs'), 'utf8'), requireMain = createRequire(path.join(__dirname, '../app/src/main.cjs'))
  const handlers = new Map(), app = new EventEmitter(); Object.assign(app, { requestSingleInstanceLock: () => true, whenReady: () => new Promise(() => {}), getPath: () => '/unused' })
  const context = { require: name => name === 'electron' ? { app, ipcMain: { handle: (key, fn) => handlers.set(key, fn) }, protocol: { registerSchemesAsPrivileged() {} }, dialog: {} } : requireMain(name), __dirname: path.join(__dirname, '../app/src'), process: { env: {}, platform: process.platform }, URL, console }
  const variables = [...source.slice(source.indexOf('let transferOperation,'), source.indexOf('let backupStatus')).matchAll(/\b(\w+Client)\b/g)].map(m => m[1])
  vm.runInNewContext(source + '\nglobalThis.qa={setWindow:w=>setupWindow=w,manager:readOperations,setClients:v=>{' + variables.map(n => n + '=v;').join('') + '}}', context)
  const groups = [...handlers.keys()].filter(k => k.endsWith(':prepare')).map(k => k.split(':')[1]); assert.equal(groups.length, 11)
  for (const group of groups) {
    const f = windowFixture(group + '.html'), wait = deferred(); context.qa.setWindow({ webContents: f.contents })
    const client = { plan: undefined, context: async () => ({ count: 1 }), prepare: async () => { await wait.promise; client.plan = { token: 'late' }; return client.plan } }
    context.qa.setClients(client)
    const getter = group === 'transfer' ? 'getTransferClient' : 'get' + group.split('-').slice(0, -1).map(v => v[0].toUpperCase() + v.slice(1)).join('') + 'TransferClient'
    context[getter] = async () => client
    assert.equal((await handlers.get('testhub-desktop:' + group + ':context')(f.event)).count, 1)
    const pending = handlers.get('testhub-desktop:' + group + ':prepare')(f.event, {}); await turn()
    context.qa.manager.cancel(f.event, context.qa.manager.status(f.event).id); wait.resolve()
    await assert.rejects(pending, { code: 'READ_CANCELLED' }); assert.equal(client.plan, undefined)
    assert.throws(() => handlers.get('testhub-desktop:' + group + ':context')({ sender: f.contents, senderFrame: { url: f.contents.mainFrame.url } }), /不受信任/)
  }
})
test('N15 navigation and close cannot release a still running operation', async () => {
  const h = harness(), wait = deferred(); let closed = 0
  const pending = h.run(() => wait.promise); await turn(); h.contents.emit('did-start-navigation', { isMainFrame: false }); assert.equal(h.manager.status(h.event).state, 'RUNNING')
  h.contents.emit('did-start-navigation', { isMainFrame: true }); const window = { webContents: h.contents, isDestroyed: () => false, close: () => { assert.equal(h.locked, false); closed++ } }
  h.manager.closeAfterCancellation(window); h.manager.closeAfterCancellation(window); assert.equal(closed, 0); assert.equal(h.locked, true)
  wait.resolve(); await assert.rejects(pending); await turn(); assert.equal(closed, 1)
})
test('N16 normal transport retains caller signals redirect limits and write requests', async () => {
  let init; const data = await jsonRequest(async (_url, options) => { init = options; return new globalThis.Response('{"data":1}') }, 'https://example.test', { method: 'POST', headers: { x: 'ok' } })
  assert.equal(data, 1); assert.equal(init.method, 'POST'); assert.equal(init.redirect, 'error'); assert.equal(init.headers.x, 'ok'); assert.equal(init.signal.aborted, false)
  const abort = new globalThis.AbortController(); abort.abort(); assert.throws(() => requestSignal(1000, abort.signal).throwIfAborted())
  await assert.rejects(jsonRequest(async () => new globalThis.Response('12345'), 'https://example.test', {}, 2), /大小限制/)
})
test('N17 each terminal outcome removes listeners and leaves no active owner reference', async () => {
  const h = harness()
  await h.run(async () => 1); await assert.rejects(h.run(async () => { throw new Error('failure') }))
  const wait = deferred(), pending = h.run(() => wait.promise); await turn(); h.cancel(); wait.resolve(); await assert.rejects(pending)
  assert.equal(h.contents.listenerCount('did-start-navigation'), 0); assert.equal(h.contents.listenerCount('destroyed'), 0); assert.equal(h.manager.active, undefined); assert.equal(h.manager.last.sender, undefined)
})
async function syncMatrix() {
  let count = 0
  for (const filename of fs.readdirSync(path.join(__dirname, '../app/src')).filter(n => n.endsWith('-sync-ipc.cjs') && n !== 'project-sync-ipc.cjs')) {
    for (const mode of filename === 'api-debug-sync-ipc.cjs' ? ['history', 'replay-drafts'] : [undefined]) {
      const file = path.join(__dirname, '../app/src', filename), required = createRequire(file), module = { exports: {} }
      let definition
      vm.runInNewContext(fs.readFileSync(file, 'utf8'), { module, exports: module.exports, require: n => n === './project-sync-ipc.cjs' ? { installProjectSync: (_options, d) => { definition = d }, syncRequest } : required(n) })
      Object.values(module.exports)[0]({}, mode); assert.ok(definition); count++
      const f = windowFixture(definition.page), handlers = new Map(), h = harness(); h.contents = f.contents
      const local = { codec: {}, url: undefined }; let starts = 0
      const manager = new ReadOperations({ trusted: e => e.sender === f.contents }); const instances = []
      class Client {
        constructor(options) { Object.assign(this, options); instances.push(this) }
        async load() { this.state = { binding: null } }
        async status() { return this.state }
        async preview(...args) { this.args = args; this.plan = { token: 'valid' }; return this.plan }
      }
      installProjectSync({ ipcMain: { handle: (key, fn) => handlers.set(key, fn) }, windows: () => ({ setup: { webContents: f.contents } }), origin: 'testhub-desktop://setup', directory: '/unused', statusRuntime: async () => local, runtime: async () => { starts++; local.url = 'http://127.0.0.1:1234'; return local }, server: async () => 'https://example.test', session: { fromPartition: () => ({ fetch: async url => new globalThis.Response(JSON.stringify({ data: url.endsWith('/me') ? { id: 'qa' } : { organizations: [], projects: {} } })) }) }, exclusive: h.exclusive, readOperations: manager }, { ...definition, Client, Store: class {} })
      await handlers.get('testhub-desktop:' + definition.channel + ':status')(f.event)
      assert.equal(manager.status(f.event).state, 'COMPLETED'); assert.equal(starts, 0)
      await handlers.get('testhub-desktop:' + definition.channel + ':context')(f.event)
      assert.equal(starts, 1)
      const result = await handlers.get('testhub-desktop:' + definition.channel + ':preview')(f.event, { sourceId: 's', targetId: 't', confirmed: true, rawFilesConfirmed: true })
      assert.equal(result.token, 'valid'); assert.equal(manager.status(f.event).state, 'COMPLETED'); assert.deepEqual(instances[0].args.slice(0, 3), ['s', 't', 'https://example.test'])
      assert.throws(() => handlers.get('testhub-desktop:' + definition.channel + ':preview')({ sender: {}, senderFrame: f.contents.mainFrame }, {}), /不受信任/)
    }
  }
  assert.equal(count, 34)
}
