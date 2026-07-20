import assert from 'node:assert/strict'
import test from 'node:test'
import { executeIsolatedScript, IsolatedScriptError } from '../src/script-executor.js'

test('isolated script can update string variables from the bounded response metadata', () => {
  const result = executeIsolatedScript({
    phase: 'POST_RESPONSE',
    source: "variables.nextStatus = response.status; variables.region = variables.region + '-verified';",
    variables: { region: 'east' }, response: { status: 201 },
  })
  assert.deepEqual(result, {
    variables: { region: 'east-verified', nextStatus: '201' },
    testSummary: { testCount: 0, passedCount: 0, failedCount: 0 },
  })
})

test('supports the bounded Postman-style pm variable and response assertion APIs', () => {
  const result = executeIsolatedScript({
    phase: 'POST_RESPONSE',
    source: `
      pm.environment.set('trace', pm.variables.get('region') + '-verified');
      pm.test('returns created', function () {
        pm.response.to.have.status(201);
        pm.expect(pm.response.code).to.eql(201);
      });
    `,
    variables: { region: 'east' }, response: { status: 201 },
  })
  assert.deepEqual(result, {
    variables: { region: 'east', trace: 'east-verified' },
    testSummary: { testCount: 1, passedCount: 1, failedCount: 0 },
  })
})

test('returns only aggregate counts for bounded pm test assertions and rejects unsupported pm APIs', () => {
  const result = executeIsolatedScript({
    phase: 'POST_RESPONSE', source: "pm.test('status', () => pm.response.to.have.status(200));",
    variables: {}, response: { status: 201 },
  })
  assert.deepEqual(result.testSummary, { testCount: 1, passedCount: 0, failedCount: 1 })
  assert.equal(JSON.stringify(result).includes('status'), false)
  assert.throws(() => executeIsolatedScript({
    phase: 'PRE_REQUEST', source: "pm.sendRequest('https://api.example.test')", variables: {}, response: null,
  }), (error) => error instanceof IsolatedScriptError && error.code === 'PM_API_UNSUPPORTED')
})

test('isolated script rejects access to host capabilities and does not expose a host object', () => {
  assert.throws(() => executeIsolatedScript({
    phase: 'PRE_REQUEST', source: 'process.exit(1)', variables: {}, response: null,
  }), (error) => error instanceof IsolatedScriptError && error.code === 'SCRIPT_CAPABILITY_FORBIDDEN')
  assert.throws(() => executeIsolatedScript({
    phase: 'PRE_REQUEST', source: 'while (true) {}', variables: {}, response: null,
  }), (error) => error instanceof IsolatedScriptError && error.code === 'SCRIPT_TIMEOUT')
})
