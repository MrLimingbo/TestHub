import { EventEmitter } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'

export function createRunnerClient({ redisFactory, redisUrl, engine, runnerId, env = process.env }) {
  if (!env.TESTHUB_DESKTOP_BROKER_URL) return redisFactory({ url: redisUrl })
  const url = new URL(env.TESTHUB_DESKTOP_BROKER_URL)
  if (url.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(url.hostname) || url.username || url.password
    || url.search || url.hash || url.pathname !== '/') throw new Error('Desktop broker must use a loopback HTTP origin')
  const token = env.TESTHUB_DESKTOP_BROKER_TOKEN
  if (!/^[0-9a-f]{64}$/.test(token ?? '')) throw new Error('Desktop broker Runner credential is required')
  return new DesktopClient({ url: `${url.origin}/internal/desktop-broker/v1/command`, token, engine, runnerId }, { delivered: new Set() })
}

class DesktopClient extends EventEmitter {
  constructor(config, shared) { super(); this.config = config; this.shared = shared; this.recovered = new Set(); this.abort = new AbortController() }
  duplicate() { return new DesktopClient(this.config, this.shared) }
  async connect() { await this.request({ operation: 'ping' }); return this }
  async quit() { this.abort.abort(); return 'OK' }
  async request(command) {
    const response = await fetch(this.config.url, {
      method: 'POST', redirect: 'error', signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(10000)]),
      headers: { 'Content-Type': 'application/json', 'X-TestHub-Desktop-Token': this.config.token },
      body: JSON.stringify({ engine: this.config.engine, runnerId: this.config.runnerId, ...command }),
    })
    if (!response.ok) throw new Error(response.status === 409 ? 'BUSYGROUP Consumer group already exists' : `Local Runner broker request failed (${response.status})`)
    return (await response.json()).result
  }
  async sendCommand(args) {
    const [command, ...rest] = args.map(String)
    if (command === 'XGROUP' && rest[0] === 'CREATE' && rest.length === 5 && rest[4] === 'MKSTREAM')
      return this.request({ operation: 'group', stream: rest[1], group: rest[2] })
    if (command === 'XADD') {
      const marker = rest.indexOf('*'); const values = rest.slice(marker + 1)
      if (marker < 1 || values.length === 0 || values.length % 2) throw new Error('Invalid local stream append')
      const fields = Object.create(null); for (let i = 0; i < values.length; i += 2) fields[values[i]] = values[i + 1]
      return this.request({ operation: 'publish', stream: rest[0], fields })
    }
    if (command === 'XACK' && rest.length >= 3) {
      const result = await this.request({ operation: 'ack', stream: rest[0], group: rest[1], ids: rest.slice(2) })
      for (const id of rest.slice(2)) this.shared.delivered.delete(`${rest[0]}:${rest[1]}:${id}`)
      return result
    }
    if (command === 'XAUTOCLAIM' && rest.length === 7 && rest[2] === this.config.runnerId && rest[5] === 'COUNT') {
      const entries = await this.request({ operation: 'claim', stream: rest[0], group: rest[1], idleMillis: Number(rest[3]), count: Number(rest[6]) })
      // The local broker scans eligible owners directly; subsequent polls continue from the durable pending set.
      return ['0-0', this.track(rest[0], rest[1], entries), []]
    }
    if (command === 'XREADGROUP') return this.read(rest)
    throw new Error(`Unsupported local Runner command: ${command}`)
  }
  track(stream, group, entries) {
    return entries.filter(([id]) => {
      const key = `${stream}:${group}:${id}`
      if (this.shared.delivered.has(key)) return false
      this.shared.delivered.add(key); return true
    })
  }
  async read(args) {
    const marker = args.indexOf('STREAMS'); const countAt = args.indexOf('COUNT'); const blockAt = args.indexOf('BLOCK')
    if (args[0] !== 'GROUP' || args[2] !== this.config.runnerId || marker < 3 || args.length !== marker + 3) throw new Error('Invalid local stream read')
    const group = args[1], stream = args[marker + 1], offset = args[marker + 2]
    const count = countAt < 0 ? 20 : Number(args[countAt + 1])
    const block = blockAt < 0 ? 0 : Number(args[blockAt + 1]); const key = `${stream}:${group}`
    if (!Number.isInteger(block) || block < 0 || block > 5000) throw new Error('Invalid local read timeout')
    const deadline = Date.now() + block
    try {
      do {
        // A successful HTTP read can be lost before its response arrives. Re-read our pending set after any transport error.
        if (offset === '>' && !this.recovered.has(key)) {
          let cursor = '0'
          while (true) {
            const pending = await this.request({ operation: 'read', stream, group, offset: cursor, count })
            const unseen = this.track(stream, group, pending)
            if (unseen.length) return [[stream, unseen]]
            if (pending.length < count) break
            cursor = pending.at(-1)[0]
          }
          this.recovered.add(key)
        }
        const entries = this.track(stream, group, await this.request({ operation: 'read', stream, group, offset, count }))
        if (entries.length) return [[stream, entries]]
        if (block === 0 || Date.now() >= deadline) return null
        await delay(Math.max(1, Math.min(100, deadline - Date.now())), undefined, { signal: this.abort.signal })
      } while (!this.abort.signal.aborted)
      return null
    } catch (error) { this.recovered.delete(key); throw error }
  }
  multi() {
    const actions = []; const chain = {}
    for (const name of ['set', 'sAdd', 'del', 'sRem']) chain[name] = (...args) => { actions.push([name, ...args]); return chain }
    chain.exec = async () => {
      const prefix = `testhub:runner:${this.config.engine}:`; const member = this.config.runnerId
      const [first, second] = actions
      if (actions.length !== 2 || second?.[1] !== `${prefix}members:v1` || second?.[2] !== member || first?.[1] !== `${prefix}heartbeat:v1:${member}`)
        throw new Error('Invalid local Runner heartbeat transaction')
      if (first[0] === 'set' && second[0] === 'sAdd') {
        await this.request({ operation: 'heartbeat', body: first[2], ttlSeconds: first[3]?.EX }); return ['OK', 1]
      }
      if (first[0] === 'del' && second[0] === 'sRem') { await this.request({ operation: 'stop' }); return [1, 1] }
      throw new Error('Unsupported local Runner transaction')
    }
    return chain
  }
}

// Only the desktop supervisor opts into the lifetime pipe; command-line/Redis deployments are unchanged.
export function watchDesktopParent(stop) {
  if (process.env.TESTHUB_DESKTOP_PARENT_WATCHDOG !== '1') return
  process.stdin.once('end', () => { void stop('desktop-parent-exit') })
  process.stdin.resume()
}
