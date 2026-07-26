const DEFAULTS = {
  host: 'localhost', port: 6380, password: 'testhub_redis_password', runnerId: 'playwright-runner-local',
  runnerVersion: '0.1.0-dev', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30,
  heartbeatSigningKey: 'testhub-ui-automation-runner-local-development-key-2026', workDirectory: '/tmp/testhub-playwright-runner',
  maxConcurrentTasks: 1, maxArtifactBytes: 25 * 1024 * 1024,
}

function integer(value, name, min, max) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) throw new Error(`${name} 必须是 ${min} 到 ${max} 的整数`)
  return parsed
}
function string(value, name, pattern) {
  if (typeof value !== 'string' || !pattern.test(value)) throw new Error(`${name} 格式不合法`)
  return value
}
function toggle(value) {
  if (value === undefined || value === '') return false
  if (value === 'true') return true
  if (value === 'false') return false
  throw new Error('RUNNER_PLAYWRIGHT_EXECUTION_ENABLED 只能是 true 或 false')
}
function url(value, name) {
  if (value === undefined || value.trim() === '') return undefined
  let parsed; try { parsed = new URL(value) } catch { throw new Error(`${name} 必须是 HTTP(S) 根地址`) }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error(`${name} 必须是不含账号、路径或查询参数的 HTTP(S) 根地址`)
  }
  return parsed.origin
}
function origins(value) {
  if (value === undefined || value.trim() === '') return []
  const list = value.split(',').map((item) => item.trim()).filter(Boolean).map((item) => url(item, 'RUNNER_ALLOWED_TARGET_ORIGINS'))
  if (list.length > 50 || new Set(list).size !== list.length) throw new Error('RUNNER_ALLOWED_TARGET_ORIGINS 不合法')
  return Object.freeze(list)
}
function redisUrl(env) {
  if (env.TESTHUB_REDIS_URL) return env.TESTHUB_REDIS_URL
  const port = integer(env.TESTHUB_REDIS_PORT ?? DEFAULTS.port, 'TESTHUB_REDIS_PORT', 1, 65535)
  return `redis://:${encodeURIComponent(env.TESTHUB_REDIS_PASSWORD ?? DEFAULTS.password)}@${env.TESTHUB_REDIS_HOST ?? DEFAULTS.host}:${port}/0`
}
export function loadConfig(env = process.env) {
  const heartbeatIntervalSeconds = integer(env.RUNNER_HEARTBEAT_INTERVAL_SECONDS ?? DEFAULTS.heartbeatIntervalSeconds, 'RUNNER_HEARTBEAT_INTERVAL_SECONDS', 2, 60)
  const heartbeatTtlSeconds = integer(env.RUNNER_HEARTBEAT_TTL_SECONDS ?? DEFAULTS.heartbeatTtlSeconds, 'RUNNER_HEARTBEAT_TTL_SECONDS', 6, 180)
  if (heartbeatTtlSeconds < heartbeatIntervalSeconds * 2) throw new Error('RUNNER_HEARTBEAT_TTL_SECONDS 至少应为心跳间隔两倍')
  const executionEnabled = toggle(env.RUNNER_PLAYWRIGHT_EXECUTION_ENABLED)
  const controlPlaneUrl = url(env.TESTHUB_CONTROL_PLANE_URL, 'TESTHUB_CONTROL_PLANE_URL')
  const allowedTargetOrigins = origins(env.RUNNER_ALLOWED_TARGET_ORIGINS)
  if (executionEnabled && (!controlPlaneUrl || allowedTargetOrigins.length === 0)) throw new Error('启用 Playwright 执行时必须配置控制面地址与目标 Origin 白名单')
  const heartbeatSigningKey = env.UI_AUTOMATION_RUNNER_HEARTBEAT_KEY ?? DEFAULTS.heartbeatSigningKey
  if (heartbeatSigningKey.length < 32 || heartbeatSigningKey.length > 512) throw new Error('UI_AUTOMATION_RUNNER_HEARTBEAT_KEY 长度必须在 32 到 512 个字符之间')
  return Object.freeze({
    redisUrl: redisUrl(env), executionEnabled, controlPlaneUrl, allowedTargetOrigins, heartbeatIntervalSeconds, heartbeatTtlSeconds, heartbeatSigningKey,
    runnerId: string(env.RUNNER_ID ?? DEFAULTS.runnerId, 'RUNNER_ID', /^[A-Za-z0-9._-]{1,64}$/),
    runnerVersion: string(env.RUNNER_VERSION ?? DEFAULTS.runnerVersion, 'RUNNER_VERSION', /^[A-Za-z0-9._+-]{1,64}$/),
    maxConcurrentTasks: integer(env.RUNNER_MAX_CONCURRENT_TASKS ?? DEFAULTS.maxConcurrentTasks, 'RUNNER_MAX_CONCURRENT_TASKS', 1, 2),
    maxArtifactBytes: integer(env.RUNNER_MAX_ARTIFACT_BYTES ?? DEFAULTS.maxArtifactBytes, 'RUNNER_MAX_ARTIFACT_BYTES', 1024, 25 * 1024 * 1024),
    workDirectory: string(env.RUNNER_WORK_DIRECTORY ?? DEFAULTS.workDirectory, 'RUNNER_WORK_DIRECTORY', /^[/A-Za-z0-9_.-]{1,512}$/),
  })
}
export function redactRedisUrl(value) { const parsed = new URL(value); return `${parsed.protocol}//${parsed.hostname}${parsed.port ? `:${parsed.port}` : ''}${parsed.pathname}` }
