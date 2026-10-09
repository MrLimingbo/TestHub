// Executed by the bundled Node runtime. Renderer processes never supply filesystem paths to this tool.
import fs from 'node:fs/promises'
import { createReadStream } from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'

const require = createRequire(import.meta.url)
const coreRequire = createRequire(require.resolve('playwright/package.json'))
const { utils } = coreRequire('playwright-core/lib/coreBundle')
const catalog = JSON.parse(await fs.readFile(new URL('./desktop-browser-catalog.json', import.meta.url), 'utf8'))
if (coreRequire('playwright-core/package.json').version !== catalog.playwrightVersion || catalog.version !== 1)
  throw new Error('浏览器组件清单与当前 TestHub 不匹配，请重新安装 TestHub。')
const components = catalog.platforms[utils.hostPlatform]
if (!components) throw new Error('当前系统版本尚未支持本地浏览器组件，请更新操作系统或使用已配置的在线运行器。')
const [command, name, archive, destination] = process.argv.slice(2)
const browsers = ['chromium', 'firefox', 'webkit']

async function sha256(file) {
  const hash = createHash('sha256')
  for await (const bytes of createReadStream(file)) hash.update(bytes)
  return hash.digest('hex')
}

if (command === 'describe') {
  if (!browsers.includes(name)) throw new Error('不支持的浏览器类型。')
  const required = [name === 'chromium' ? 'chromium-headless-shell' : name, 'ffmpeg', ...(utils.hostPlatform === 'win64' ? ['winldd'] : [])]
  console.log(JSON.stringify({ playwrightVersion: catalog.playwrightVersion, platform: utils.hostPlatform, browser: name,
    components: required.map(component => {
      const item = components.find(item => item.name === component)
      if (!item) throw new Error('浏览器组件清单不完整。')
      return item
    }) }))
} else if (command === 'extract') {
  const component = components.find(item => item.name === name)
  if (!component || !path.isAbsolute(archive || '') || !path.isAbsolute(destination || '')) throw new Error('组件解压参数无效。')
  const metadata = await fs.lstat(archive)
  if (!metadata.isFile() || metadata.size !== component.bytes || await sha256(archive) !== component.sha256)
    throw new Error('浏览器下载文件校验失败。')
  await fs.mkdir(destination, { mode: 0o700 })
  let count = 0, expandedBytes = 0
  // Use the pinned Playwright extractor to preserve native executable permissions and framework symlinks.
  await utils.extractZip(archive, { dir: destination, onEntry(entry) {
    expandedBytes += entry.uncompressedSize
    if (++count > 20000 || expandedBytes > 2 * 1024 ** 3) throw new Error('浏览器组件展开大小超出限制。')
  } })
  const executable = path.join(destination, component.executable)
  const real = await fs.realpath(executable)
  if (!real.startsWith(await fs.realpath(destination) + path.sep)) throw new Error('浏览器可执行文件超出组件目录。')
  await fs.chmod(executable, 0o755)
  console.log(JSON.stringify({ extracted: true }))
} else if (command === 'verify') {
  if (!browsers.includes(name) || !path.isAbsolute(archive || '')) throw new Error('组件验证参数无效。')
  const cache = process.env.PLAYWRIGHT_BROWSERS_PATH || ''
  if (!path.isAbsolute(cache) || cache === path.parse(cache).root) throw new Error('组件缓存目录无效。')
  const work = await fs.mkdtemp(path.join(archive, 'browser-probe-'))
  let instance
  if (process.env.TESTHUB_DESKTOP_PARENT_WATCHDOG === '1') {
    process.stdin.once('end', () => { void (async () => { await instance?.close().catch(() => {}); process.exit(130) })() })
    process.stdin.resume(); process.stdin.unref?.()
  }
  try {
    const factory = (await import('playwright'))[name]
    instance = await factory.launch({ headless: true })
    const context = await instance.newContext({ viewport: { width: 320, height: 240 }, recordVideo: { dir: work } })
    await context.route('**/*', route => route.abort())
    const page = await context.newPage()
    await page.setContent('<title>TestHub</title><p>Browser component ready</p>')
    await page.screenshot({ path: path.join(work, 'probe.png') })
    await page.waitForTimeout(300)
    await context.close()
    const files = await fs.readdir(work)
    const video = files.find(file => file.endsWith('.webm'))
    if (!video || (await fs.stat(path.join(work, video))).size === 0) throw new Error('浏览器录屏组件验证失败。')
    console.log(JSON.stringify({ verified: true, browser: name, version: instance.version() }))
  } finally { await instance?.close(); await fs.rm(work, { recursive: true, force: true }) }
} else throw new Error('不支持的浏览器组件操作。')
