import { createClient } from 'redis'
import { loadConfig, redactRedisUrl } from './config.js'
import { JMeterRunner } from './runner.js'

const config = loadConfig()
const commandClient = createClient({ url: config.redisUrl })
const readClient = commandClient.duplicate()
const cancelReadClient = commandClient.duplicate()
const runner = new JMeterRunner({ commandClient, readClient, cancelReadClient, config })
for (const client of [commandClient, readClient, cancelReadClient]) client.on('error', (error) => console.error('JMeter Runner Redis 连接错误', error.message))
console.info(`JMeter Runner 正在连接 Redis：${redactRedisUrl(config.redisUrl)}`)
await runner.start()
async function stop(signal) { console.info(`收到 ${signal}，正在停止 JMeter Runner`); await runner.stop(); process.exit(0) }
process.once('SIGINT', () => void stop('SIGINT'))
process.once('SIGTERM', () => void stop('SIGTERM'))
