import vm from 'node:vm'

const MAX_SCRIPT_LENGTH = 8 * 1024
const MAX_VARIABLES = 100
const MAX_VARIABLE_VALUE_LENGTH = 10_000
const MAX_TESTS_PER_SCRIPT = 100
const VARIABLE_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/
const FORBIDDEN_GLOBAL = /\b(require|import|export|process|globalThis|global|module|exports|eval|webassembly|worker|sharedarraybuffer|atomics|settimeout|setinterval|queuemicrotask|fetch|xmlhttprequest|deno|bun)\b/i
const DYNAMIC_FUNCTION = /\bFunction\b/
const UNSUPPORTED_PM_API = /\b(?:postman|pm)\s*\.\s*(?:globals|collectionVariables|cookies|sendRequest|execution|request|visualizer|vault|require)\b|\bpm\s*\.\s*response\s*\.\s*(?:json|text|headers|cookies)\b/i

export class IsolatedScriptError extends Error {
  constructor(code) {
    super(code)
    this.code = code
  }
}

export function validateIsolatedScript(source) {
  if (typeof source !== 'string' || source.length > MAX_SCRIPT_LENGTH) throw new IsolatedScriptError('SCRIPT_SOURCE_INVALID')
  if (source && FORBIDDEN_GLOBAL.test(source)) throw new IsolatedScriptError('SCRIPT_CAPABILITY_FORBIDDEN')
  if (source && DYNAMIC_FUNCTION.test(source)) throw new IsolatedScriptError('SCRIPT_CAPABILITY_FORBIDDEN')
  if (source && UNSUPPORTED_PM_API.test(source)) throw new IsolatedScriptError('PM_API_UNSUPPORTED')
}

function normalizeVariables(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new IsolatedScriptError('SCRIPT_VARIABLES_INVALID')
  const entries = Object.entries(value)
  if (entries.length > MAX_VARIABLES) throw new IsolatedScriptError('SCRIPT_VARIABLES_INVALID')
  const result = {}
  for (const [name, rawValue] of entries) {
    if (!VARIABLE_NAME.test(name) || !['string', 'number', 'boolean'].includes(typeof rawValue)) {
      throw new IsolatedScriptError('SCRIPT_VARIABLES_INVALID')
    }
    const normalized = String(rawValue)
    if (normalized.length > MAX_VARIABLE_VALUE_LENGTH) throw new IsolatedScriptError('SCRIPT_VARIABLES_INVALID')
    result[name] = normalized
  }
  return result
}

function normalizeTestSummary(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
    || !Number.isInteger(value.testCount) || !Number.isInteger(value.passedCount)) {
    throw new IsolatedScriptError('SCRIPT_RESULT_INVALID')
  }
  if (value.testCount < 0 || value.testCount > MAX_TESTS_PER_SCRIPT
    || value.passedCount < 0 || value.passedCount > value.testCount) {
    throw new IsolatedScriptError('SCRIPT_RESULT_INVALID')
  }
  return Object.freeze({ testCount: value.testCount, passedCount: value.passedCount,
    failedCount: value.testCount - value.passedCount })
}

/**
 * No host object or host callback enters the context. Values cross the boundary only through JSON, and all code
 * generation, module loading, timers, I/O, and asynchronous primitives are absent. Container resource limits remain
 * the outer isolation boundary; this synchronous context additionally enforces a short CPU deadline.
 */
export function executeIsolatedScript({ phase, source, variables, response }) {
  validateIsolatedScript(source)
  const initialVariables = normalizeVariables(variables)
  if (!source) return Object.freeze({ variables: initialVariables, testSummary: normalizeTestSummary({ testCount: 0, passedCount: 0 }) })
  const payload = JSON.stringify({ variables: initialVariables, response: { status: response?.status ?? null } })
  const sandbox = Object.create(null)
  const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } })
  const wrapped = `"use strict";
const __input = JSON.parse(${JSON.stringify(payload)});
const variables = __input.variables;
const response = Object.freeze(__input.response);
const __tests = [];
const __has = (name) => Object.prototype.hasOwnProperty.call(variables, String(name));
const __get = (name) => __has(name) ? variables[String(name)] : undefined;
const __set = (name, value) => {
  const key = String(name);
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key)) throw new Error('__PM_VARIABLE_NAME_INVALID__');
  variables[key] = String(value);
};
const __unset = (name) => { delete variables[String(name)]; };
const __scope = Object.freeze({
  get: __get,
  has: (name) => __has(name),
  set: __set,
  unset: __unset,
  toObject: () => JSON.parse(JSON.stringify(variables)),
  replaceIn: (template) => String(template).replace(/\\{\\{([A-Za-z_][A-Za-z0-9_]{0,63})\\}\\}/g, (match, name) => __has(name) ? variables[name] : match),
});
const __assert = (condition) => { if (!condition) throw new Error('__PM_ASSERTION_FAILED__'); };
const __expect = (actual) => {
  const equal = (expected) => __assert(Object.is(actual, expected));
  return Object.freeze({ to: Object.freeze({
    equal,
    eql: equal,
    be: Object.freeze({
      true: () => __assert(actual === true),
      false: () => __assert(actual === false),
      null: () => __assert(actual === null),
      undefined: () => __assert(actual === undefined),
      oneOf: (values) => __assert(Array.isArray(values) && values.some((value) => Object.is(actual, value))),
    }),
    have: Object.freeze({ status: (expected) => __assert(actual === expected) }),
  }) });
};
const pm = Object.freeze({
  variables: __scope,
  environment: __scope,
  response: Object.freeze({
    code: response.status,
    to: Object.freeze({ have: Object.freeze({ status: (expected) => __assert(response.status === expected) }) }),
  }),
  expect: __expect,
  test: (name, callback) => {
    if (typeof name !== 'string' || !name.trim() || name.length > 160 || typeof callback !== 'function') {
      throw new Error('__PM_TEST_INVALID__');
    }
    if (__tests.length >= ${MAX_TESTS_PER_SCRIPT}) throw new Error('__PM_TEST_LIMIT_EXCEEDED__');
    try {
      callback();
      __tests.push(true);
    } catch {
      __tests.push(false);
    }
  },
});
${source}
JSON.stringify({ variables, testSummary: { testCount: __tests.length, passedCount: __tests.filter((passed) => passed).length } });`
  try {
    const output = new vm.Script(wrapped, { filename: `testhub-${phase}.js` }).runInContext(context, {
      timeout: 150,
      breakOnSigint: true,
    })
    const result = JSON.parse(output)
    return Object.freeze({ variables: normalizeVariables(result.variables), testSummary: normalizeTestSummary(result.testSummary) })
  } catch (error) {
    if (error instanceof IsolatedScriptError) throw error
    if (String(error?.message ?? '').includes('Script execution timed out')) throw new IsolatedScriptError('SCRIPT_TIMEOUT')
    throw new IsolatedScriptError('SCRIPT_EXECUTION_FAILED')
  }
}
