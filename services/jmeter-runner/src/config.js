const DEFAULTS = {
  host: 'localhost', port: 6380, password: 'testhub_redis_password', runnerId: 'jmeter-runner-local',
  runnerVersion: '0.1.0-dev', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30,
  heartbeatSigningKey: 'testhub-performance-runner-local-development-key-2026',
  jmeterBin: 'jmeter', workDirectory: '/tmp/testhub-jmeter-runner', maxArchiveBytes: 20 * 1024 * 1024,
  maxConcurrentTasks: 1, taskClaimIdleSeconds: 45,
}

function integer(value, name, minimum, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) throw new Error(`${name} 必须是 ${minimum} 到 ${maximum} 的整数`)
  return parsed
}

function value(source, name, pattern) {
  if (typeof source !== 'string' || !pattern.test(source)) throw new Error(`${name} 格式不合法`)
  return source
}

function redisUrl(env) {
  if (env.TESTHUB_REDIS_URL) return env.TESTHUB_REDIS_URL
  const host = env.TESTHUB_REDIS_HOST ?? DEFAULTS.host
  const port = integer(env.TESTHUB_REDIS_PORT ?? DEFAULTS.port, 'TESTHUB_REDIS_PORT', 1, 65535)
  const password = env.TESTHUB_REDIS_PASSWORD ?? DEFAULTS.password
  return `redis://:${encodeURIComponent(password)}@${host}:${port}/0`
}

function enabled(value) {
  if (value === undefined || value === null || value === '') return false
  if (value === 'true') return true
  if (value === 'false') return false
  throw new Error('RUNNER_JMETER_EXECUTION_ENABLED 只能是 true 或 false')
}

function controlPlaneUrl(value) {
  if (value === undefined || value === null || value.trim() === '') return undefined
  let parsed
  try { parsed = new URL(value) } catch { throw new Error('TESTHUB_CONTROL_PLANE_URL 必须是 HTTP(S) 根地址') }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('TESTHUB_CONTROL_PLANE_URL 必须是不含账号、路径或查询参数的 HTTP(S) 根地址')
  }
  return parsed.origin
}

function allowedOrigins(value) {
  if (value === undefined || value === null || value.trim() === '') return []
  const origins = value.split(',').map((item) => item.trim()).filter(Boolean).map((item) => {
    let parsed
    try { parsed = new URL(item) } catch { throw new Error('RUNNER_ALLOWED_TARGET_ORIGINS 必须是 HTTP(S) Origin 列表') }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password
      || parsed.pathname !== '/' || parsed.search || parsed.hash) throw new Error('RUNNER_ALLOWED_TARGET_ORIGINS 必须只包含 HTTP(S) Origin')
    return parsed.origin
  })
  if (origins.length > 50 || new Set(origins).size !== origins.length) throw new Error('RUNNER_ALLOWED_TARGET_ORIGINS 不合法')
  return Object.freeze(origins)
}

export function loadConfig(env = process.env) {
  const heartbeatIntervalSeconds = integer(env.RUNNER_HEARTBEAT_INTERVAL_SECONDS ?? DEFAULTS.heartbeatIntervalSeconds,
    'RUNNER_HEARTBEAT_INTERVAL_SECONDS', 2, 60)
  const heartbeatTtlSeconds = integer(env.RUNNER_HEARTBEAT_TTL_SECONDS ?? DEFAULTS.heartbeatTtlSeconds,
    'RUNNER_HEARTBEAT_TTL_SECONDS', 6, 180)
  if (heartbeatTtlSeconds < heartbeatIntervalSeconds * 2) throw new Error('RUNNER_HEARTBEAT_TTL_SECONDS 至少应为心跳间隔的两倍')
  const heartbeatSigningKey = env.PERFORMANCE_RUNNER_HEARTBEAT_KEY ?? DEFAULTS.heartbeatSigningKey
  if (heartbeatSigningKey.length < 32 || heartbeatSigningKey.length > 512) throw new Error('PERFORMANCE_RUNNER_HEARTBEAT_KEY 长度必须在 32 到 512 个字符之间')
  const jmeterExecutionEnabled = enabled(env.RUNNER_JMETER_EXECUTION_ENABLED)
  const runtimeControlPlaneUrl = controlPlaneUrl(env.TESTHUB_CONTROL_PLANE_URL)
  const allowedTargetOrigins = allowedOrigins(env.RUNNER_ALLOWED_TARGET_ORIGINS)
  const maxArchiveBytes = integer(env.RUNNER_MAX_ARCHIVE_BYTES ?? DEFAULTS.maxArchiveBytes,
    'RUNNER_MAX_ARCHIVE_BYTES', 1_024, 20 * 1024 * 1024)
  const maxConcurrentTasks = integer(env.RUNNER_MAX_CONCURRENT_TASKS ?? DEFAULTS.maxConcurrentTasks,
    'RUNNER_MAX_CONCURRENT_TASKS', 1, 8)
  const taskClaimIdleSeconds = integer(env.RUNNER_TASK_CLAIM_IDLE_SECONDS ?? DEFAULTS.taskClaimIdleSeconds,
    'RUNNER_TASK_CLAIM_IDLE_SECONDS', 10, 600)
  if (jmeterExecutionEnabled && (!runtimeControlPlaneUrl || allowedTargetOrigins.length === 0)) {
    throw new Error('启用 JMeter 执行时必须配置 TESTHUB_CONTROL_PLANE_URL 与 RUNNER_ALLOWED_TARGET_ORIGINS')
  }
  return Object.freeze({
    redisUrl: redisUrl(env), runnerId: value(env.RUNNER_ID ?? DEFAULTS.runnerId, 'RUNNER_ID', /^[A-Za-z0-9._-]{1,64}$/),
    runnerVersion: value(env.RUNNER_VERSION ?? DEFAULTS.runnerVersion, 'RUNNER_VERSION', /^[A-Za-z0-9._+-]{1,64}$/),
    heartbeatIntervalSeconds, heartbeatTtlSeconds, heartbeatSigningKey, jmeterExecutionEnabled,
    controlPlaneUrl: runtimeControlPlaneUrl, allowedTargetOrigins,
    maxArchiveBytes, maxConcurrentTasks, taskClaimIdleSeconds,
    jmeterBin: value(env.JMETER_BIN ?? DEFAULTS.jmeterBin, 'JMETER_BIN', /^[A-Za-z0-9_./-]{1,512}$/),
    workDirectory: value(env.RUNNER_WORK_DIRECTORY ?? DEFAULTS.workDirectory, 'RUNNER_WORK_DIRECTORY', /^[/A-Za-z0-9_.-]{1,512}$/),
  })
}

export function redactRedisUrl(source) {
  const url = new URL(source)
  return `${url.protocol}//${url.hostname}${url.port ? `:${url.port}` : ''}${url.pathname}`
}
