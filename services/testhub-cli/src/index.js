#!/usr/bin/env node
import { CliError, run } from './cli.js'

try {
  await run(process.argv.slice(2))
} catch (error) {
  const message = error instanceof CliError ? error.message : 'TestHub CLI 执行失败。'
  process.stderr.write(`${message}\n`)
  process.exitCode = error instanceof CliError ? error.exitCode : 1
}
