// Maintainer-only generator. The desktop app uses the resulting pinned catalog, never curl or npm.
import fs from 'node:fs/promises'
import { createReadStream } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const serviceRequire = createRequire(path.join(root, 'services/playwright-runner/package.json'))
const playwrightRequire = createRequire(serviceRequire.resolve('playwright/package.json'))
const version = playwrightRequire('playwright-core/package.json').version

async function hash(file) {
  const value = createHash('sha256')
  for await (const bytes of createReadStream(file)) value.update(bytes)
  return value.digest('hex')
}

if (process.argv[2] === '--probe') {
  // This adapter is pinned to Playwright 1.61.0; its private registry layout must be rechecked on upgrades.
  if (version !== '1.61.0') throw new Error('Review the registry adapter before changing Playwright version')
  const { registry: { registry }, utils } = playwrightRequire('playwright-core/lib/coreBundle')
  const names = ['chromium-headless-shell', 'firefox', 'webkit', 'ffmpeg', ...(utils.hostPlatform === 'win64' ? ['winldd'] : [])]
  const artifacts = names.map(name => {
    const item = registry.findExecutable(name)
    if (!item?.directory || !item.executablePath() || !item.downloadURLs?.length) throw new Error('Missing browser descriptor: ' + name)
    if (item.downloadURLs.some(url => !url.startsWith('https://'))) throw new Error('Non-HTTPS browser download')
    return { name, revision: item.revision, directory: path.basename(item.directory),
      executable: path.relative(item.directory, item.executablePath()).split(path.sep).join('/'), urls: item.downloadURLs }
  })
  console.log(JSON.stringify({ platform: utils.hostPlatform, artifacts }))
} else if (process.argv[2] === '--download') {
  const platforms = ['mac14', 'mac14-arm64', 'mac15', 'mac15-arm64', 'mac26', 'mac26-arm64', 'win64']
  const plans = []
  for (const platform of platforms) {
    const { stdout } = await execute(process.execPath, [fileURLToPath(import.meta.url), '--probe'], {
      env: { ...process.env, PLAYWRIGHT_HOST_PLATFORM_OVERRIDE: platform } })
    plans.push(JSON.parse(stdout))
  }
  const cache = path.join(root, '.testhub/browser-artifacts', 'playwright-' + version)
  await fs.mkdir(cache, { recursive: true })
  const unique = [...new Map(plans.flatMap(plan => plan.artifacts).map(item => [item.urls[0], item])).values()]
  const results = new Map()
  let next = 0
  async function worker() {
    while (next < unique.length) {
      const item = unique[next++], url = item.urls[0]
      const file = path.join(cache, createHash('sha256').update(url).digest('hex') + '.zip')
      try { await fs.access(file) }
      catch (error) {
        if (error.code !== 'ENOENT') throw error
        console.log('Downloading ' + url)
        try {
          let downloaded = false, lastError
          for (const source of item.urls) {
            try {
              await execute('curl', ['--fail', '--location', '--silent', '--show-error', '--max-time', '120', '--speed-time', '30', '--speed-limit', '32768',
                '--max-filesize', String(400 * 1024 * 1024), '--proto', '=https', '--proto-redir', '=https', '--output', file + '.part', source],
              { timeout: 125000, maxBuffer: 1024 * 1024 })
              downloaded = true; break
            } catch (error) { lastError = error; console.log('Retrying official mirror for ' + item.name) }
          }
          if (!downloaded) throw lastError
          await fs.rename(file + '.part', file)
        } finally { await fs.rm(file + '.part', { force: true }) }
      }
      const sha256 = await hash(file), bytes = (await fs.stat(file)).size
      results.set(url, { sha256, bytes })
      console.log(`Pinned ${item.name}: ${bytes} bytes ${sha256}`)
    }
  }
  const completed = await Promise.allSettled([worker(), worker(), worker()])
  const failures = completed.filter(result => result.status === 'rejected')
  if (failures.length) throw new AggregateError(failures.map(result => result.reason), 'Browser catalog download failed; successful downloads remain cached for retry.')
  const catalog = { version: 1, playwrightVersion: version,
    checksumProvenance: 'SHA-256 computed from the pinned Playwright release official HTTPS download URLs; not vendor-published checksums.',
    platforms: Object.fromEntries(plans.map(plan => [plan.platform, plan.artifacts.map(item => ({ ...item, ...results.get(item.urls[0]) }))])) }
  const output = path.join(root, 'services/playwright-runner/src/desktop-browser-catalog.json')
  await fs.writeFile(output, JSON.stringify(catalog, null, 2) + '\n')
  console.log('Generated ' + output)
} else {
  throw new Error('Use --download to fetch official artifacts and regenerate the pinned desktop browser catalog.')
}
