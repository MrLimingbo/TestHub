<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface TrendPoint {
  date: string
  completedExecutions: number
  passedExecutions: number
  failedExecutions: number
}

const props = defineProps<{ points: TrendPoint[] }>()
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined

function render() {
  if (!chartRef.value) return
  chart ??= echarts.init(chartRef.value)
  chart.setOption({
    animationDuration: 500,
    grid: { left: 42, right: 18, top: 26, bottom: 32 },
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 0, itemWidth: 8, itemHeight: 8, textStyle: { color: '#6f7c94', fontSize: 11 } },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.points.map((point) => point.date.slice(5).replace('-', '/')),
      axisLine: { lineStyle: { color: '#dce3f2' } },
      axisLabel: { color: '#86909c', fontSize: 10 },
    },
    yAxis: {
      type: 'value', minInterval: 1,
      splitLine: { lineStyle: { color: '#edf0f7', type: 'dashed' } },
      axisLabel: { color: '#a1a8b3', fontSize: 10 },
    },
    series: [
      {
        name: '已判定执行', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5,
        data: props.points.map((point) => point.completedExecutions),
        lineStyle: { color: '#3167f6', width: 3 }, itemStyle: { color: '#3167f6' },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(49,103,246,.25)' }, { offset: 1, color: 'rgba(49,103,246,0)' },
        ]) },
      },
      {
        name: '通过', type: 'line', smooth: true, symbol: 'circle', symbolSize: 4,
        data: props.points.map((point) => point.passedExecutions),
        lineStyle: { color: '#36b78d', width: 2 }, itemStyle: { color: '#36b78d' },
      },
    ],
  }, { notMerge: true })
}

onMounted(() => { render(); window.addEventListener('resize', render) })
watch(() => props.points, render, { deep: true })
onBeforeUnmount(() => { window.removeEventListener('resize', render); chart?.dispose() })
</script>

<template><div ref="chartRef" class="chart-panel" role="img" aria-label="项目质量执行趋势图"></div></template>
