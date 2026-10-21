import path from 'node:path'

// Node launched by pwsh inherits PS7 modules that Windows PowerShell cannot load.
// These native checks only need the modules shipped with Windows PowerShell 5.1.
export function windowsPowerShellEnvironment(source = process.env) {
  const env = Object.fromEntries(Object.entries(source).filter(([key]) => key.toLowerCase() !== 'psmodulepath'))
  const root = Object.entries(source).find(([key]) => key.toLowerCase() === 'systemroot')?.[1]
  if (!root) throw new Error('Windows PowerShell checks require SystemRoot')
  env.PSModulePath = path.win32.join(root, 'System32', 'WindowsPowerShell', 'v1.0', 'Modules')
  return env
}
