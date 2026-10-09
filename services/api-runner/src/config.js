const DEFAULTS = {
  host: 'localhost',
  port: 6380,
  password: 'testhub_redis_password',
  heartbeatIntervalSeconds: 10,
  heartbeatTtlSeconds: 30,
  runnerId: 'api-runner-local',
  runnerVersion: '0.1.0-dev',
  precheckDelayMilliseconds: 3000,
}

function integer(value, name, minimum, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} 必须是 ${minimum} 到 ${maximum} 的整数`)
  }
  return parsed
}

function nonEmpty(value, name, pattern) {
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw new Error(`${name} 格式不合法`)
  }
  return value
}

function redisUrl(env) {
  if (env.TESTHUB_REDIS_URL) return env.TESTHUB_REDIS_URL
  const host = env.TESTHUB_REDIS_HOST ?? DEFAULTS.host
  const port = integer(env.TESTHUB_REDIS_PORT ?? DEFAULTS.port, 'TESTHUB_REDIS_PORT', 1, 65535)
  const password = env.TESTHUB_REDIS_PASSWORD ?? DEFAULTS.password
  return `redis://:${encodeURIComponent(password)}@${host}:${port}/0`
}

function optionalSigningKey(value) {
  if (value === undefined || value === null || value.trim() === '') return undefined
  if (value.length < 32 || value.length > 512) {
    throw new Error('RUNNER_HEARTBEAT_KEY 长度必须在 32 到 512 个字符之间')
  }
  return value
}

function optionalControlPlaneUrl(value) {
  if (value === undefined || value === null || value.trim() === '') return undefined
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new Error('TESTHUB_CONTROL_PLANE_URL 必须是 HTTP(S) 地址')
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('TESTHUB_CONTROL_PLANE_URL 必须是不含账号、路径或查询参数的 HTTP(S) 根地址')
  }
  return parsed.origin
}

function enabled(value, name) {
  if (value === undefined || value === null || value === '') return false
  if (value === 'true') return true
  if (value === 'false') return false
  throw new Error(`${name} 只能是 true 或 false`)
}

function allowedOrigins(value) {
  if (value === undefined || value === null || value.trim() === '') return []
  const origins = value.split(',').map((item) => item.trim()).filter(Boolean).map((item) => {
    let parsed
    try { parsed = new URL(item) } catch { throw new Error('RUNNER_ALLOWED_ORIGINS 必须是 HTTP(S) Origin 列表') }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password
      || parsed.pathname !== '/' || parsed.search || parsed.hash) {
      throw new Error('RUNNER_ALLOWED_ORIGINS 必须只包含 HTTP(S) Origin')
    }
    return parsed.origin
  })
  if (new Set(origins).size !== origins.length || origins.length > 50) throw new Error('RUNNER_ALLOWED_ORIGINS 不合法')
  return Object.freeze(origins)
}

export function loadConfig(env = process.env) {
  const heartbeatIntervalSeconds = integer(
    env.RUNNER_HEARTBEAT_INTERVAL_SECONDS ?? DEFAULTS.heartbeatIntervalSeconds,
    'RUNNER_HEARTBEAT_INTERVAL_SECONDS', 2, 60,
  )
  const heartbeatTtlSeconds = integer(
    env.RUNNER_HEARTBEAT_TTL_SECONDS ?? DEFAULTS.heartbeatTtlSeconds,
    'RUNNER_HEARTBEAT_TTL_SECONDS', 6, 180,
  )
  if (heartbeatTtlSeconds < heartbeatIntervalSeconds * 2) {
    throw new Error('RUNNER_HEARTBEAT_TTL_SECONDS 至少应为心跳间隔的两倍')
  }
  const precheckDelayMilliseconds = integer(
    env.RUNNER_PRECHECK_DELAY_MILLISECONDS ?? DEFAULTS.precheckDelayMilliseconds,
    'RUNNER_PRECHECK_DELAY_MILLISECONDS', 0, 30000,
  )
  const heartbeatSigningKey = optionalSigningKey(env.RUNNER_HEARTBEAT_KEY)
  const controlPlaneUrl = optionalControlPlaneUrl(env.TESTHUB_CONTROL_PLANE_URL)
  const httpExecutionEnabled = enabled(env.RUNNER_HTTP_EXECUTION_ENABLED, 'RUNNER_HTTP_EXECUTION_ENABLED')
  const allowedHttpOrigins = allowedOrigins(env.RUNNER_ALLOWED_ORIGINS)
  const allowPrivateNetwork = enabled(env.RUNNER_DESKTOP_NETWORK_ACCESS, 'RUNNER_DESKTOP_NETWORK_ACCESS')
  if (allowPrivateNetwork && (!env.TESTHUB_DESKTOP_BROKER_URL || !env.TESTHUB_DESKTOP_BROKER_TOKEN)) {
    throw new Error('内网执行授权仅支持已配置本地任务队列凭据的桌面运行进程')
  }
  if (controlPlaneUrl && !heartbeatSigningKey) {
    throw new Error('配置 TESTHUB_CONTROL_PLANE_URL 时必须同时配置 RUNNER_HEARTBEAT_KEY')
  }
  if (httpExecutionEnabled && (!controlPlaneUrl || !heartbeatSigningKey || allowedHttpOrigins.length === 0)) {
    throw new Error('启用 HTTP 执行时必须配置控制面地址、签名密钥和 RUNNER_ALLOWED_ORIGINS')
  }
  return Object.freeze({
    redisUrl: redisUrl(env),
    runnerId: nonEmpty(env.RUNNER_ID ?? DEFAULTS.runnerId, 'RUNNER_ID', /^[A-Za-z0-9._-]{1,64}$/),
    runnerVersion: nonEmpty(env.RUNNER_VERSION ?? DEFAULTS.runnerVersion, 'RUNNER_VERSION', /^[A-Za-z0-9._+-]{1,64}$/),
    heartbeatIntervalSeconds,
    heartbeatTtlSeconds,
    precheckDelayMilliseconds,
    heartbeatSigningKey,
    controlPlaneUrl,
    httpExecutionEnabled,
    allowedHttpOrigins,
    allowPrivateNetwork,
  })
}

export function redactRedisUrl(value) {
  const url = new URL(value)
  return `${url.protocol}//${url.hostname}${url.port ? `:${url.port}` : ''}${url.pathname}`
}
