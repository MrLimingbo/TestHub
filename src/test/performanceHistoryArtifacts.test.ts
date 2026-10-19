import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import PerformanceHistoryArtifacts from '@/components/PerformanceHistoryArtifacts.vue'
const wrappers: ReturnType<typeof mount>[] = [], history = 'A'.repeat(26) + '.' + 'B'.repeat(26)
const artifact = { key: history + '.JMETER_LOG', sourceArtifactId: 'C'.repeat(26), artifactType: 'JMETER_LOG', originalName: 'jmeter.log', contentType: 'text/plain', sizeBytes: 0, checksum: 'd'.repeat(64), archivedAt: '2026-10-19T01:00:00Z', nativeRecord: false }
const response = (data: unknown) => ({ ok: true, json: async () => ({ data }) })
async function setup(projectId = 'p1') { const w = mount(PerformanceHistoryArtifacts, { props: { projectId, historyKey: history }, attachTo: document.body, global: { plugins: [ArcoVue] } }); wrappers.push(w); await flushPromises(); return w }
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); document.body.innerHTML = '' })
it('provides scoped read-only downloads, including empty logs, with size and digest', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response([artifact])))
  const w = await setup(); const link = w.find('a[download]')
  expect(link.attributes('href')).toBe(`/api/v1/projects/p1/performance/artifacts/${artifact.key}/download`)
  expect(w.text()).toContain('0 B'); expect(w.text()).toContain(artifact.checksum); expect(w.text()).toContain('可能含敏感信息')
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('late files from an old project cannot appear after a switch', async () => {
  let finish!: (v: unknown) => void
  vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('/p1/') ? new Promise(resolve => { finish = resolve }) : Promise.resolve(response([]))))
  const w = await setup(); await w.setProps({ projectId: 'p2' }); finish(response([artifact])); await flushPromises()
  expect(w.find('a[download]').exists()).toBe(false); expect(w.text()).toContain('当前历史尚无原始工件')
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
  const w = await setup(); expect(w.find('a[download]').exists()).toBe(false); expect(w.text()).toContain('原始工件列表不完整')
})
