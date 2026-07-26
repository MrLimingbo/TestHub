import { createClient } from 'redis'
import { loadConfig, redactRedisUrl } from './config.js'
import { ApiRunner } from './runner.js'

const config = loadConfig()
const commandClient = createClient({ url: config.redisUrl })
const readClient = commandClient.duplicate()
const cancelReadClient = commandClient.duplicate()
const runner = new ApiRunner({ commandClient, readClient, cancelReadClient, config })

commandClient.on('error', (error) => console.error('API Runner Redis 命令连接错误', error.message))
readClient.on('error', (error) => console.error('API Runner Redis 读取连接错误', error.message))
cancelReadClient.on('error', (error) => console.error('API Runner Redis 取消指令连接错误', error.message))

console.info(`API Runner 正在连接 Redis：${redactRedisUrl(config.redisUrl)}`)
await runner.start()

async function stop(signal) {
  console.info(`收到 ${signal}，正在停止 API Runner`)
  await runner.stop()
  process.exit(0)
}

process.once('SIGINT', () => void stop('SIGINT'))
process.once('SIGTERM', () => void stop('SIGTERM'))
