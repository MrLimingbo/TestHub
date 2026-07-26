import { createClient } from 'redis'
import { loadConfig, redactRedisUrl } from './config.js'
import { PlaywrightRunner } from './runner.js'

const config = loadConfig()
const commandClient = createClient({ url: config.redisUrl }); const readClient = commandClient.duplicate(); const cancelClient = commandClient.duplicate()
const runner = new PlaywrightRunner({ commandClient, readClient, cancelClient, config })
for (const client of [commandClient, readClient, cancelClient]) client.on('error', (error) => console.error('Playwright Runner Redis 连接错误', error.message))
console.info(`Playwright Runner 正在连接 Redis：${redactRedisUrl(config.redisUrl)}`)
await runner.start()
async function stop(signal) { console.info(`收到 ${signal}，正在停止 Playwright Runner`); await runner.stop(); process.exit(0) }
process.once('SIGINT', () => void stop('SIGINT')); process.once('SIGTERM', () => void stop('SIGTERM'))
