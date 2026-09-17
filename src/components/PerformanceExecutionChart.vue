<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface Metric {
  occurredAt: string
  threadGroupName: string
  throughputPerSecond: number
  p95LatencyMillis: number
  errorCount: number
}

const props = defineProps<{ metrics: Metric[] }>()
const element = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let resizeObserver: ResizeObserver | undefined
const resize = () => chart?.resize()

function render() {
  if (!element.value) return
  chart ??= echarts.init(element.value)
  const total = props.metrics.filter((metric) => metric.threadGroupName === '__TOTAL__')
  chart.setOption({
    animationDuration: 260,
    color: ['#3167f6', '#f7a928', '#f53f3f'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0, data: ['TPS', 'P95（ms）', '累计错误'], textStyle: { color: '#4e5969' } },
    grid: { left: 42, right: 42, top: 52, bottom: 28 },
    xAxis: { type: 'category', boundaryGap: false, data: total.map((metric) => new Date(metric.occurredAt).toLocaleTimeString('zh-CN', { hour12: false })), axisLine: { lineStyle: { color: '#dce3f2' } } },
    yAxis: [
      { type: 'value', name: 'TPS', splitLine: { lineStyle: { color: '#edf0f7', type: 'dashed' } } },
      { type: 'value', name: '延迟 / 错误', splitLine: { show: false } },
    ],
    series: [
      { name: 'TPS', type: 'line', smooth: true, showSymbol: false, data: total.map((metric) => metric.throughputPerSecond), areaStyle: { opacity: 0.1 } },
      { name: 'P95（ms）', type: 'line', smooth: true, showSymbol: false, yAxisIndex: 1, data: total.map((metric) => metric.p95LatencyMillis) },
      { name: '累计错误', type: 'line', smooth: true, showSymbol: false, yAxisIndex: 1, data: total.map((metric) => metric.errorCount) },
    ],
  }, { notMerge: true })
}

onMounted(() => {
  render()
  resizeObserver = new ResizeObserver(resize)
  if (element.value) resizeObserver.observe(element.value)
  window.addEventListener('resize', resize)
})
watch(() => props.metrics, render, { deep: true })
onBeforeUnmount(() => { resizeObserver?.disconnect(); window.removeEventListener('resize', resize); chart?.dispose() })
</script>

<template><div ref="element" class="execution-chart" role="img" aria-label="压测指标趋势图"></div></template>

<style scoped>.execution-chart{height:270px;width:100%}</style>
