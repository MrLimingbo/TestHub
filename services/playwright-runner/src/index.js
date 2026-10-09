import { createRunnerClient, watchDesktopParent } from '../../runner-transport/client.mjs'
import { loadConfig, redactRedisUrl } from './config.js'
import { PlaywrightRunner } from './runner.js'

const createClient = process.env.TESTHUB_DESKTOP_BROKER_URL ? undefined : (await import('redis')).createClient
const config = loadConfig()
const commandClient = createRunnerClient({ redisFactory: createClient, redisUrl: config.redisUrl, engine: 'playwright', runnerId: config.runnerId }); const readClient = commandClient.duplicate(); const cancelClient = commandClient.duplicate()
const runner = new PlaywrightRunner({ commandClient, readClient, cancelClient, config })
for (const client of [commandClient, readClient, cancelClient]) client.on('error', (error) => console.error('Playwright Runner 传输连接错误', error.message))
console.info(process.env.TESTHUB_DESKTOP_BROKER_URL ? 'Playwright Runner 正在连接本地任务队列' : `Playwright Runner 正在连接 Redis：${redactRedisUrl(config.redisUrl)}`)
await runner.start()
async function stop(signal) { console.info(`收到 ${signal}，正在停止 Playwright Runner`); await runner.stop(); process.exit(0) }
process.once('SIGINT', () => void stop('SIGINT')); process.once('SIGTERM', () => void stop('SIGTERM'))

watchDesktopParent(stop)
