import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import * as echarts from 'echarts'
import DashboardTrendChart from '@/components/DashboardTrendChart.vue'

vi.mock('echarts', () => ({ init: vi.fn(), graphic: { LinearGradient: class {} } }))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('Dashboard trend chart', () => {
  it('resizes for container and window changes and releases both listeners on unmount', () => {
    const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }
    vi.mocked(echarts.init).mockReturnValue(chart as unknown as echarts.ECharts)
    let changed!: ResizeObserverCallback
    const observe = vi.fn(), disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: ResizeObserverCallback) { changed = callback }
      observe = observe
      disconnect = disconnect
    })
    const wrapper = mount(DashboardTrendChart, { props: { points: [] } })
    expect(observe).toHaveBeenCalledWith(wrapper.element)
    changed([], {} as ResizeObserver)
    window.dispatchEvent(new Event('resize'))
    expect(chart.resize).toHaveBeenCalledTimes(2)
    expect(chart.setOption).toHaveBeenCalledOnce()
    wrapper.unmount()
    window.dispatchEvent(new Event('resize'))
    expect(chart.resize).toHaveBeenCalledTimes(2)
    expect(disconnect).toHaveBeenCalledOnce()
    expect(chart.dispose).toHaveBeenCalledOnce()
  })
})
