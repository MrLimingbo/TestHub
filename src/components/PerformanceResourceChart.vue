<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface Resource {
  occurredAt: string
  cpuPercent: number
  residentMemoryBytes: number
}

const props = defineProps<{ resources: Resource[] }>()
const element = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined

function render() {
  if (!element.value) return
  chart ??= echarts.init(element.value)
  chart.setOption({
    animationDuration: 260,
    color: ['#3167f6', '#00b42a'],
    tooltip: { trigger: 'axis' },
    legend: { data: ['CPU（%）', '常驻内存（MB）'], textStyle: { color: '#4e5969' } },
    grid: { left: 48, right: 54, top: 36, bottom: 28 },
    xAxis: { type: 'category', boundaryGap: false, data: props.resources.map((resource) => new Date(resource.occurredAt).toLocaleTimeString('zh-CN', { hour12: false })), axisLine: { lineStyle: { color: '#dce3f2' } } },
    yAxis: [
      { type: 'value', name: 'CPU %', splitLine: { lineStyle: { color: '#edf0f7', type: 'dashed' } } },
      { type: 'value', name: 'MB', splitLine: { show: false } },
    ],
    series: [
      { name: 'CPU（%）', type: 'line', smooth: true, showSymbol: false, data: props.resources.map((resource) => resource.cpuPercent), areaStyle: { opacity: 0.1 } },
      { name: '常驻内存（MB）', type: 'line', smooth: true, showSymbol: false, yAxisIndex: 1,
        data: props.resources.map((resource) => Number((resource.residentMemoryBytes / 1024 / 1024).toFixed(2))) },
    ],
  }, { notMerge: true })
}

onMounted(() => { render(); window.addEventListener('resize', render) })
watch(() => props.resources, render, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', render); chart?.dispose() })
</script>

<template><div ref="element" class="resource-chart" role="img" aria-label="JMeter 进程 CPU 与常驻内存趋势图"></div></template>

<style scoped>.resource-chart{height:250px;width:100%}</style>
