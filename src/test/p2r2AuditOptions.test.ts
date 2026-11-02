import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import AuditLogPage from '@/pages/AuditLogPage.vue'
const wrappers: VueWrapper[] = []
const Select = { name: 'QaSelect', props: { modelValue: { type: String, default: '' } }, emits: ['update:modelValue', 'change'], template: '<div><slot /></div>' }
const Option = { name: 'QaOption', props: { value: { type: String, required: true } }, template: '<span>{{ value }}</span>' }
const stamp = '2026-10-30T00:00:00Z'
const row = (action: string) => ({ id: action, action, resourceType: 'QA', outcome: 'SUCCESS', requestId: 'fixture', createdAt: stamp })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: '受控加载失败' }), { status })
function deferred() { let resolve!: (v: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { resolve, promise } }
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks() })
async function setup(override?: (url: string) => Promise<Response> | undefined) {
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const fetcher = vi.fn((url: string) => override?.(url) ?? Promise.resolve(response(url.includes('/audit-logs/actions') ? ['A', 'B', 'OLDER'] : new URL(url, 'https://qa.invalid').searchParams.get('action') ? [row(new URL(url, 'https://qa.invalid').searchParams.get('action')!)] : [row('A'), row('B')])))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(AuditLogPage, { global: { plugins: [ArcoVue], stubs: { teleport: true, 'a-select': Select, 'a-option': Option }, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises(); return { w, fetcher }
}
const options = async (w: VueWrapper) => w.findAllComponents({ name: 'QaOption' }).map(o => o.props('value'))
const select = async (w: VueWrapper, value: string) => { const s = w.findComponent({ name: 'QaSelect' }); s.vm.$emit('update:modelValue', value); s.vm.$emit('change', value); await flushPromises() }
const refresh = async (w: VueWrapper) => { w.findAllComponents({ name: 'Button' }).find(b => b.text().trim() === '刷新')!.vm.$emit('click'); await flushPromises() }
it('TC-P2R2-D04-01: an action filter never shrinks the options', async () => { const { w } = await setup(); await select(w, 'A'); expect(await options(w)).toContain('B'); await select(w, 'B'); expect(await options(w)).toContain('A') })
it('TC-P2R2-D04-02: metadata includes actions outside the current rows', async () => { const { w } = await setup(); expect(await options(w)).toContain('OLDER') })
it('TC-P2R2-D04-03: an empty row query leaves the action collection intact', async () => { const { w } = await setup(url => url.includes('action=') ? Promise.resolve(response([])) : undefined); await select(w, 'A'); expect(await options(w)).toContain('B') })
it('TC-P2R2-D04-04: permission loss clears cached action names and rows', async () => { let denied = false; const { w } = await setup(() => denied ? Promise.resolve(response({}, 403)) : undefined); denied = true; await refresh(w); expect(await options(w)).toEqual([]); expect(w.findComponent({ name: 'Table' }).props('data')).toEqual([]) })
it('TC-P2R2-D04-05: metadata failure exposes stale options without pretending they are fresh', async () => { let fail = false; const { w } = await setup(url => fail && url.includes('/actions') ? Promise.resolve(response({}, 503)) : undefined); fail = true; await refresh(w); expect(await options(w)).toContain('A'); expect(w.text()).toContain('动作选项'); expect(w.text()).toContain('旧') })
it('TC-P2R2-D04-05: delayed metadata cannot overwrite a newer selection', async () => {
  const gate = deferred(); let mode = 0
  const { w } = await setup(url => url.includes('/actions') && mode ? mode === 1 ? gate.promise : Promise.resolve(response(['NEW'])) : undefined)
  mode = 1; await select(w, 'A'); mode = 2; await select(w, 'B'); gate.resolve(response(['OLD'])); await flushPromises(); expect(await options(w)).toEqual(['NEW'])
})
it('TC-P2R2-D04-06: row requests retain their original limit', async () => { const { fetcher } = await setup(); expect(fetcher.mock.calls.some(([url]) => url.includes('/audit-logs?limit=100'))).toBe(true) })
