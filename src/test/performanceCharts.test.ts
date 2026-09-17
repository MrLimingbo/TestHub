import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import * as echarts from 'echarts'
import PerformanceExecutionChart from '@/components/PerformanceExecutionChart.vue'
import PerformanceResourceChart from '@/components/PerformanceResourceChart.vue'
vi.mock('echarts', () => ({ init: vi.fn() }))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })
describe('Performance charts', () => {
  for (const component of [PerformanceExecutionChart, PerformanceResourceChart]) {
    it(`resizes ${component.__name} when its drawer expands and disconnects on unmount`, () => {
      const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }
      vi.mocked(echarts.init).mockReturnValue(chart as unknown as echarts.ECharts)
      let changed!: ResizeObserverCallback
      const observe = vi.fn(); const disconnect = vi.fn()
      vi.stubGlobal('ResizeObserver', class {
        constructor(callback: ResizeObserverCallback) { changed = callback }
        observe = observe
        disconnect = disconnect
      })
      const wrapper = mount(component, { props: { metrics: [], resources: [] } })
      expect(observe).toHaveBeenCalledWith(wrapper.element)
      changed([], {} as ResizeObserver)
      expect(chart.resize).toHaveBeenCalledOnce()
      wrapper.unmount()
      expect(disconnect).toHaveBeenCalledOnce()
      expect(chart.dispose).toHaveBeenCalledOnce()
    })
  }
})
