import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import UiAutomationHistoryArtifacts from '@/components/UiAutomationHistoryArtifacts.vue'
const wrappers: ReturnType<typeof mount>[] = [], history = 'A'.repeat(26) + '.' + 'B'.repeat(26)
const artifact = { key: history + '.' + 'C'.repeat(26), sourceArtifactId: 'C'.repeat(26), artifactType: 'TRACE', originalName: 'trace.zip', contentType: 'application/zip', sizeBytes: 0, checksum: 'd'.repeat(64), archivedAt: '2026-10-19T01:00:00Z', nativeRecord: false }
const response = (data: unknown) => ({ ok: true, json: async () => ({ data }) })
async function setup(projectId = 'p1') { const w = mount(UiAutomationHistoryArtifacts, { props: { projectId, historyKey: history }, attachTo: document.body, global: { plugins: [ArcoVue] } }); wrappers.push(w); await flushPromises(); return w }
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); document.body.innerHTML = '' })
it('provides scoped read-only downloads, including empty logs, with size and digest', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response([artifact])))
  const w = await setup(); const link = w.find('a[download]')
  expect(link.attributes('href')).toBe(`/api/v1/projects/p1/ui-automation/artifacts/${artifact.key}/download`)
  expect(w.text()).toContain('0 B'); expect(w.text()).toContain(artifact.checksum); expect(w.text()).toContain('可能含敏感页面信息')
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('late files from an old project cannot appear after a switch', async () => {
  let finish!: (v: unknown) => void
  vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('/p1/') ? new Promise(resolve => { finish = resolve }) : Promise.resolve(response([]))))
  const w = await setup(); await w.setProps({ projectId: 'p2' }); finish(response([artifact])); await flushPromises()
  expect(w.find('a[download]').exists()).toBe(false); expect(w.text()).toContain('暂无原始产物')
})
it('history changes immediately discard the previous download link', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.includes(history) ? [artifact] : [])))
  const w = await setup(); expect(w.find('a[download]').exists()).toBe(true)
  await w.setProps({ historyKey: 'D'.repeat(26) + '.' + 'E'.repeat(26) }); await flushPromises()
  expect(w.find('a[download]').exists()).toBe(false)
})
it('failed refresh clears prior files and exposes an actionable error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response([artifact])).mockResolvedValue({ ok: false, json: async () => ({ message: '存储不可读，请核对' }) }))
  const w = await setup(); await w.find('button').trigger('click'); await flushPromises()
  expect(w.text()).toContain('存储不可读，请核对'); expect(w.find('a[download]').exists()).toBe(false)
})
it('files attributed to a different origin are rejected without links', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response([{ ...artifact, key: 'other.JMETER_LOG' }])))
  const w = await setup(); expect(w.find('a[download]').exists()).toBe(false); expect(w.text()).toContain('报告产物列表不完整')
})

it('loads screenshot and video previews only after explicit selection and allows multiple screenshots',async()=>{
 const first={...artifact,sourceArtifactId:'D'.repeat(26),key:history+'.'+'D'.repeat(26),artifactType:'SCREENSHOT',originalName:'screenshot.png',contentType:'image/png'},second={...first,sourceArtifactId:'E'.repeat(26),key:history+'.'+'E'.repeat(26)},video={...artifact,sourceArtifactId:'F'.repeat(26),key:history+'.'+'F'.repeat(26),artifactType:'VIDEO',originalName:'video.webm',contentType:'video/webm'}
 vi.stubGlobal('fetch',vi.fn(async()=>response([first,second,video])));const w=await setup();expect(w.findAll('a[download]')).toHaveLength(3);expect(w.find('img').exists()).toBe(false);expect(w.find('video').exists()).toBe(false)
 await w.findAll('button').find(b=>b.text()==='预览视频')!.trigger('click');expect(w.find('video').attributes('src')).toBe(`/api/v1/projects/p1/ui-automation/artifacts/${video.key}/view`)
 await w.findAll('button').find(b=>b.text()==='关闭预览')!.trigger('click');expect(w.find('video').exists()).toBe(false)
})
