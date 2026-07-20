import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { CliError, parseArgs, run } from '../src/cli.js'

const environment = {
  TESTHUB_URL: 'https://testhub.example.com/control-plane',
  TESTHUB_PROJECT_ID: '01PROJECT',
  TESTHUB_SCENARIO_ID: '01SCENARIO',
  TESTHUB_EXECUTION_ID: '01EXECUTION',
  TESTHUB_TOKEN: 'thp_secret_should_never_be_printed',
}

function output() {
  let value = ''
  return { stream: { write: (chunk) => { value += chunk } }, read: () => value }
}

test('execution submit sends the PAT without printing it', async () => {
  const stdout = output()
  const requests = []
  await run(['execution', 'submit', '--timeout', '120'], environment, {
    stdout: stdout.stream,
    fetchImpl: async (url, init) => {
      requests.push({ url, init })
      return Response.json({ data: { id: '01EXECUTION', taskId: '01TASK', status: 'QUEUED' } })
    },
  })

  assert.equal(requests[0].url, 'https://testhub.example.com/control-plane/api/v1/projects/01PROJECT/api-automation/scenarios/01SCENARIO/executions')
  assert.equal(requests[0].init.headers.Authorization, `Bearer ${environment.TESTHUB_TOKEN}`)
  assert.equal(requests[0].init.body, '{"timeoutSeconds":120}')
  assert.deepEqual(JSON.parse(stdout.read()), { executionId: '01EXECUTION', taskId: '01TASK', status: 'QUEUED' })
  assert.equal(stdout.read().includes(environment.TESTHUB_TOKEN), false)
})

test('execution report prints only the report contract from the response envelope', async () => {
  const stdout = output()
  const report = { contract: 'testhub.api-automation.execution-report', reportVersion: 5, execution: { id: '01EXECUTION' } }
  await run(['execution', 'report'], environment, {
    stdout: stdout.stream,
    fetchImpl: async () => Response.json({ data: report }),
  })
  assert.deepEqual(JSON.parse(stdout.read()), report)
})

test('execution wait polls active executions and exits successfully when the Runner succeeds', async () => {
  const stdout = output()
  const statuses = [
    { id: '01EXECUTION', taskId: '01TASK', status: 'RUNNING', statusCode: null },
    { id: '01EXECUTION', taskId: '01TASK', status: 'SUCCEEDED', statusCode: 'COMPLETED' },
  ]
  let currentTime = 0
  const result = await run(['execution', 'wait', '--wait-timeout', '30', '--poll-interval', '2'], environment, {
    stdout: stdout.stream,
    now: () => currentTime,
    sleep: async (milliseconds) => { currentTime += milliseconds },
    fetchImpl: async () => Response.json({ data: statuses.shift() }),
  })

  assert.deepEqual(result, {
    executionId: '01EXECUTION',
    taskId: '01TASK',
    status: 'SUCCEEDED',
    statusCode: 'COMPLETED',
    polls: 2,
    waitedMilliseconds: 2000,
    waitStatus: 'COMPLETED',
  })
  assert.deepEqual(JSON.parse(stdout.read()), result)
})

test('execution wait emits a safe terminal summary and a CI failure exit code', async () => {
  const stdout = output()
  await assert.rejects(
    run(['execution', 'wait'], environment, {
      stdout: stdout.stream,
      now: () => 0,
      fetchImpl: async () => Response.json({ data: { id: '01EXECUTION', taskId: '01TASK', status: 'FAILED', statusCode: 'ASSERTION_FAILED', statusMessage: 'must not be printed' } }),
    }),
    (error) => error instanceof CliError && error.exitCode === 2 && error.message.includes('FAILED'),
  )
  assert.deepEqual(JSON.parse(stdout.read()), {
    executionId: '01EXECUTION',
    taskId: '01TASK',
    status: 'FAILED',
    statusCode: 'ASSERTION_FAILED',
    polls: 1,
    waitedMilliseconds: 0,
    waitStatus: 'COMPLETED',
  })
  assert.equal(stdout.read().includes('must not be printed'), false)
  assert.equal(stdout.read().includes(environment.TESTHUB_TOKEN), false)
})

test('execution wait returns a distinct CI exit code when client polling times out', async () => {
  const stdout = output()
  let currentTime = 0
  let requests = 0
  await assert.rejects(
    run(['execution', 'wait', '--wait-timeout', '10', '--poll-interval', '5'], environment, {
      stdout: stdout.stream,
      now: () => currentTime,
      sleep: async (milliseconds) => { currentTime += milliseconds },
      fetchImpl: async () => {
        requests += 1
        return Response.json({ data: { id: '01EXECUTION', taskId: '01TASK', status: 'RUNNING', statusCode: null } })
      },
    }),
    (error) => error instanceof CliError && error.exitCode === 3 && error.message.includes('超时'),
  )
  assert.equal(requests, 3)
  assert.deepEqual(JSON.parse(stdout.read()), {
    executionId: '01EXECUTION',
    taskId: '01TASK',
    status: 'RUNNING',
    statusCode: null,
    polls: 3,
    waitedMilliseconds: 10000,
    waitStatus: 'CLIENT_TIMED_OUT',
  })
})

test('execution run submits, waits, verifies, and archives one CI report with one JSON result', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'testhub-cli-'))
  const target = join(directory, 'report.json')
  const stdout = output()
  const content = Buffer.from('{"contract":"testhub.api-automation.execution-report"}')
  const sha256 = createHash('sha256').update(content).digest('hex')
  const eTag = `"${sha256}"`
  const statuses = [
    { id: '01RUN', taskId: '01TASK', status: 'RUNNING', statusCode: null },
    { id: '01RUN', taskId: '01TASK', status: 'SUCCEEDED', statusCode: 'COMPLETED' },
  ]
  const requests = []
  let currentTime = 0
  try {
    const result = await run(['execution', 'run', '--timeout', '120', '--wait-timeout', '30', '--poll-interval', '2', '--report-output', target], environment, {
      stdout: stdout.stream,
      now: () => currentTime,
      sleep: async (milliseconds) => { currentTime += milliseconds },
      fetchImpl: async (url, init) => {
        requests.push({ url, init })
        if (init.method === 'POST') return Response.json({ data: { id: '01RUN', taskId: '01TASK', status: 'QUEUED' } })
        if (url.endsWith('/integrity')) return Response.json({ data: { sha256, eTag } })
        if (url.endsWith('/report/export')) return new Response(content, { headers: { ETag: eTag, 'X-TestHub-Report-SHA256': sha256 } })
        return Response.json({ data: statuses.shift() })
      },
    })
    assert.deepEqual(result, {
      submission: { executionId: '01RUN', taskId: '01TASK', status: 'QUEUED' },
      execution: { executionId: '01RUN', taskId: '01TASK', status: 'SUCCEEDED', statusCode: 'COMPLETED', polls: 2, waitedMilliseconds: 2000, waitStatus: 'COMPLETED' },
      reportArtifact: { status: 'exported', output: target, sha256, bytes: content.byteLength },
    })
    assert.deepEqual(JSON.parse(stdout.read()), result)
    assert.equal(requests.length, 5)
    assert.equal(await readFile(target, 'utf8'), content.toString('utf8'))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('execution run archives a terminal failure report before returning the CI failure code', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'testhub-cli-'))
  const target = join(directory, 'failed-report.json')
  const stdout = output()
  const content = Buffer.from('{"contract":"testhub.api-automation.execution-report"}')
  const sha256 = createHash('sha256').update(content).digest('hex')
  const eTag = `"${sha256}"`
  try {
    await assert.rejects(
      run(['execution', 'run', '--report-output', target], environment, {
        stdout: stdout.stream,
        now: () => 0,
        fetchImpl: async (url, init) => {
          if (init.method === 'POST') return Response.json({ data: { id: '01RUN', taskId: '01TASK', status: 'QUEUED' } })
          if (url.endsWith('/integrity')) return Response.json({ data: { sha256, eTag } })
          if (url.endsWith('/report/export')) return new Response(content, { headers: { ETag: eTag, 'X-TestHub-Report-SHA256': sha256 } })
          return Response.json({ data: { id: '01RUN', taskId: '01TASK', status: 'FAILED', statusCode: 'ASSERTION_FAILED', statusMessage: 'must not be printed' } })
        },
      }),
      (error) => error instanceof CliError && error.exitCode === 2,
    )
    const result = JSON.parse(stdout.read())
    assert.equal(result.execution.status, 'FAILED')
    assert.equal(result.reportArtifact.status, 'exported')
    assert.equal(await readFile(target, 'utf8'), content.toString('utf8'))
    assert.equal(stdout.read().includes('must not be printed'), false)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('execution export verifies metadata and atomically writes the report artifact', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'testhub-cli-'))
  const target = join(directory, 'report.json')
  const content = Buffer.from('{"contract":"testhub.api-automation.execution-report"}')
  const sha256 = createHash('sha256').update(content).digest('hex')
  const eTag = `"${sha256}"`
  const stdout = output()
  const requestedUrls = []
  try {
    await run(['execution', 'export', '--output', target], environment, {
      stdout: stdout.stream,
      fetchImpl: async (url) => {
        requestedUrls.push(url)
        if (url.endsWith('/integrity')) return Response.json({ data: { sha256, eTag } })
        return new Response(content, { headers: { ETag: eTag, 'X-TestHub-Report-SHA256': sha256 } })
      },
    })
    assert.equal(await readFile(target, 'utf8'), content.toString('utf8'))
    assert.equal(requestedUrls.length, 2)
    const result = JSON.parse(stdout.read())
    assert.equal(result.status, 'exported')
    assert.equal(result.sha256, sha256)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('execution export honors a not-modified response without writing a file', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'testhub-cli-'))
  const target = join(directory, 'report.json')
  const content = Buffer.from('{"contract":"testhub.api-automation.execution-report"}')
  const sha256 = createHash('sha256').update(content).digest('hex')
  const eTag = `"${sha256}"`
  const stdout = output()
  try {
    await run(['execution', 'export', '--output', target, '--if-none-match', eTag], environment, {
      stdout: stdout.stream,
      fetchImpl: async (url, init) => {
        if (url.endsWith('/integrity')) return Response.json({ data: { sha256, eTag } })
        assert.equal(init.headers['If-None-Match'], eTag)
        return new Response(null, { status: 304, headers: { ETag: eTag, 'X-TestHub-Report-SHA256': sha256 } })
      },
    })
    const result = JSON.parse(stdout.read())
    assert.equal(result.status, 'not-modified')
    await assert.rejects(readFile(target), { code: 'ENOENT' })
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('invalid CLI input is rejected before an HTTP request is made', async () => {
  await assert.rejects(
    run(['execution', 'submit', '--base-url', 'https://token@example.com', '--timeout', '2'], environment, { fetchImpl: async () => assert.fail('must not request') }),
    (error) => error instanceof CliError && error.message.includes('服务地址'),
  )
  await assert.rejects(
    run(['execution', 'report', '--unknown', 'value'], environment, { fetchImpl: async () => assert.fail('must not request') }),
    (error) => error instanceof CliError && error.message.includes('--unknown'),
  )
  await assert.rejects(
    run(['execution', 'wait', '--poll-interval', '0'], environment, { fetchImpl: async () => assert.fail('must not request') }),
    (error) => error instanceof CliError && error.message.includes('轮询间隔'),
  )
  assert.throws(() => parseArgs(['execution']), CliError)
})
