<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ type?: 'line' | 'bar'; accent?: string }>(), { type: 'line', accent: '#3167f6' })
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined

const render = () => {
  if (!chartRef.value) return
  chart ??= echarts.init(chartRef.value)
  const series = props.type === 'bar'
    ? [{ type: 'bar', barWidth: 18, data: [72, 86, 66, 91, 78, 95, 88], itemStyle: { color: props.accent, borderRadius: [5, 5, 0, 0] } }]
    : [
        { type: 'line', smooth: true, symbol: 'none', data: [62, 78, 70, 88, 82, 101, 96], lineStyle: { color: props.accent, width: 3 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: `${props.accent}42` }, { offset: 1, color: `${props.accent}00` }]) } },
        { type: 'line', smooth: true, symbol: 'none', data: [48, 56, 62, 60, 72, 76, 83], lineStyle: { color: '#52c7a3', width: 2 } },
      ]
  chart.setOption({
    animationDuration: 700,
    grid: { left: 42, right: 18, top: 18, bottom: 34 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', boundaryGap: props.type === 'bar', data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'], axisLine: { lineStyle: { color: '#dce3f2' } }, axisLabel: { color: '#86909c' } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf0f7', type: 'dashed' } }, axisLabel: { color: '#a1a8b3' } },
    series,
  })
}

onMounted(() => { render(); window.addEventListener('resize', render) })
watch(() => [props.type, props.accent], render)
onBeforeUnmount(() => { window.removeEventListener('resize', render); chart?.dispose() })
</script>

<template><div ref="chartRef" class="chart-panel" role="img" aria-label="趋势图"></div></template>
