import { readFileSync } from 'node:fs'
import { executeInContext, IsolatedScriptError } from './script-executor.js'

try {
  const result = executeInContext(JSON.parse(readFileSync(0, 'utf8')))
  process.stdout.write(JSON.stringify(result))
} catch (error) {
  process.stdout.write(JSON.stringify({ error: error instanceof IsolatedScriptError ? error.code : 'SCRIPT_EXECUTION_FAILED' }))
}
