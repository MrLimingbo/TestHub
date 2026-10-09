// Prepare a native development/test bundle. Release jobs must prepare and verify each target architecture separately.
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFile } from 'node:child_process'
import { createRequire } from 'node:module'
import { promisify } from 'node:util'
import { createHash, randomUUID } from 'node:crypto'
const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const target = `${process.platform}-${process.arch}`
const directory = path.join(root, '.testhub/desktop-runtime', target)
const staging = directory + '-' + randomUUID()
const javaHome = process.env.JAVA_HOME
if (!javaHome) throw new Error('JAVA_HOME must point to a Java 21 JDK for bundle preparation')
const executable = name => process.platform === 'win32' ? name + '.exe' : name
const javaVersion = await execute(path.join(javaHome, 'bin', executable('java')), ['-version'])
if (!/version "21[."]/.test(javaVersion.stderr)) throw new Error('The desktop backend requires Java 21')
if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node 22 or newer is required')
const sources = { backend: path.join(root, 'services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar'), frontend: path.join(root, 'dist') }
await fs.access(sources.backend); await fs.access(path.join(sources.frontend, 'index.html'))
await fs.mkdir(staging, { recursive: true })
try {
  await execute(path.join(javaHome, 'bin', executable('jlink')), ['--add-modules', 'ALL-MODULE-PATH', '--strip-debug', '--no-man-pages', '--no-header-files', '--output', path.join(staging, 'jre')], { timeout: 120000 })
  await fs.mkdir(path.join(staging, 'node'), { recursive: true })
  await fs.copyFile(process.execPath, path.join(staging, 'node', executable('node')))
  await fs.chmod(path.join(staging, 'node', executable('node')), 0o755)
  const nodeHome = process.platform === 'win32' ? path.dirname(process.execPath) : path.dirname(path.dirname(process.execPath))
  await fs.copyFile(path.join(nodeHome, 'LICENSE'), path.join(staging, 'node', 'LICENSE'))
  if (process.platform === 'darwin') {
    const binaries = [path.join(staging, 'jre/bin/java'), path.join(staging, 'node/node')]
    async function collectLibraries(directory) {
      for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
        const file = path.join(directory, entry.name)
        if (entry.isDirectory()) await collectLibraries(file)
        else if (entry.isFile() && entry.name.endsWith('.dylib')) binaries.push(file)
      }
    }
    await collectLibraries(path.join(staging, 'jre'))
    for (const binary of binaries) {
      const { stdout } = await execute('otool', ['-L', binary])
      const dependencies = stdout.split('\n').slice(1).map(line => line.trim().split(' (')[0]).filter(Boolean)
      const external = dependencies.filter(file => file.startsWith('/') && !file.startsWith('/System/Library/') && !file.startsWith('/usr/lib/'))
      if (external.length) throw new Error(`Runtime depends on non-system libraries: ${path.basename(binary)} -> ${external.join(', ')}. Use a standalone vendor JDK, not a Homebrew JDK.`)
    }
  }
  const agentManifest = path.join(staging, 'watchdog-manifest.mf')
  await fs.writeFile(agentManifest, 'Manifest-Version: 1.0\nPremain-Class: io.testhub.platform.desktop.DesktopEngineWatchdog\n\n')
  await execute(path.join(javaHome, 'bin', executable('jar')), ['--create', '--file', path.join(staging, 'jmeter-watchdog.jar'),
    '--manifest', agentManifest, '-C', path.join(root, 'services/platform-api/target/classes'),
    'io/testhub/platform/desktop/DesktopEngineWatchdog.class'])
  await fs.rm(agentManifest)
  await fs.copyFile(sources.backend, path.join(staging, 'platform-api.jar'))
  await fs.cp(sources.frontend, path.join(staging, 'web'), { recursive: true })
  for (const service of ['api-runner', 'jmeter-runner', 'playwright-runner', 'appium-runner', 'runner-transport']) {
    const destination = path.join(staging, 'services', service)
    await fs.mkdir(destination, { recursive: true })
    if (service !== 'runner-transport') {
      await fs.cp(path.join(root, 'services', service, 'src'), path.join(destination, 'src'), { recursive: true })
      await fs.copyFile(path.join(root, 'services', service, 'package.json'), path.join(destination, 'package.json'))
    } else await fs.copyFile(path.join(root, 'services/runner-transport/client.mjs'), path.join(destination, 'client.mjs'))
  }
  const playwrightRequire = createRequire(path.join(root, 'services/playwright-runner/package.json'))
  const playwrightPackage = playwrightRequire.resolve('playwright/package.json')
  const corePackage = createRequire(playwrightPackage).resolve('playwright-core/package.json')
  for (const [name, source] of [['playwright', playwrightPackage], ['playwright-core', corePackage]]) {
    await fs.cp(path.dirname(source), path.join(staging, 'services/playwright-runner/node_modules', name), { recursive: true })
  }
  const manifest = { version: 1, platform: process.platform, arch: process.arch,
    java: 'jre/bin/' + executable('java'), node: 'node/' + executable('node'), backend: 'platform-api.jar', frontend: 'web',
    apiRunner: 'services/api-runner/src/index.js', jmeterRunner: 'services/jmeter-runner/src/index.js', jmeterWatchdog: 'jmeter-watchdog.jar',
    appiumRunner: 'services/appium-runner/src/index.js', playwrightRunner: 'services/playwright-runner/src/index.js', playwrightTool: 'services/playwright-runner/src/desktop-browser-tool.mjs', backendSha256: createHash('sha256').update(await fs.readFile(sources.backend)).digest('hex'),
    nodeVersion: process.versions.node, javaVersion: javaVersion.stderr.trim(), preparedAt: new Date().toISOString(), distributionReady: false }
  await fs.writeFile(path.join(staging, 'runtime.json'), JSON.stringify(manifest, null, 2) + '\n')
  // Preserve the preceding test bundle; never overwrite a running process's runtime files.
  try { await fs.rename(directory, directory + '-previous-' + Date.now()) } catch (error) { if (error.code !== 'ENOENT') throw error }
  await fs.rename(staging, directory)
  console.log('Prepared native development runtime: ' + directory)
} catch (error) { await fs.rm(staging, { recursive: true, force: true }); throw error }
