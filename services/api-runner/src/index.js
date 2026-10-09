import { createRunnerClient, watchDesktopParent } from '../../runner-transport/client.mjs'
import { loadConfig, redactRedisUrl } from './config.js'
import { ApiRunner } from './runner.js'

const createClient = process.env.TESTHUB_DESKTOP_BROKER_URL ? undefined : (await import('redis')).createClient
const config = loadConfig()
const commandClient = createRunnerClient({ redisFactory: createClient, redisUrl: config.redisUrl, engine: 'api', runnerId: config.runnerId })
const readClient = commandClient.duplicate()
const cancelReadClient = commandClient.duplicate()
const runner = new ApiRunner({ commandClient, readClient, cancelReadClient, config })

commandClient.on('error', (error) => console.error('API Runner 传输命令连接错误', error.message))
readClient.on('error', (error) => console.error('API Runner 传输读取连接错误', error.message))
cancelReadClient.on('error', (error) => console.error('API Runner 传输取消指令连接错误', error.message))

console.info(process.env.TESTHUB_DESKTOP_BROKER_URL ? 'API Runner 正在连接本地任务队列' : `API Runner 正在连接 Redis：${redactRedisUrl(config.redisUrl)}`)
await runner.start()

async function stop(signal) {
  console.info(`收到 ${signal}，正在停止 API Runner`)
  await runner.stop()
  process.exit(0)
}

process.once('SIGINT', () => void stop('SIGINT'))
process.once('SIGTERM', () => void stop('SIGTERM'))

watchDesktopParent(stop)
