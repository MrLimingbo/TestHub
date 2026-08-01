<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface ExecutionSummary {
  passed: number
  failed: number
  blocked: number
  notRun: number
}

const props = defineProps<{
  assetCounts: number[]
  execution: ExecutionSummary
}>()

const assetChartRef = ref<HTMLDivElement>()
const executionChartRef = ref<HTMLDivElement>()
let assetChart: echarts.ECharts | undefined
let executionChart: echarts.ECharts | undefined

function render() {
  if (assetChartRef.value) {
    assetChart ??= echarts.init(assetChartRef.value)
    assetChart.setOption({
      animationDuration: 450,
      grid: { left: 40, right: 16, top: 20, bottom: 36 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'category',
        data: ['需求', '用例', '计划', '缺陷'],
        axisTick: { show: false },
        axisLine: { lineStyle: { color: '#dce3f2' } },
        axisLabel: { color: '#6f7c94', fontSize: 11 },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: '#edf0f7', type: 'dashed' } },
        axisLabel: { color: '#a1a8b3', fontSize: 10 },
      },
      series: [{
        name: '资产数量',
        type: 'bar',
        barWidth: 30,
        data: props.assetCounts.map((value, index) => ({
          value,
          itemStyle: { color: ['#3167f6', '#42b995', '#7758e8', '#ef9141'][index], borderRadius: [6, 6, 0, 0] },
        })),
      }],
    }, { notMerge: true })
  }

  if (executionChartRef.value) {
    executionChart ??= echarts.init(executionChartRef.value)
    const data = [
      { name: '通过', value: props.execution.passed, itemStyle: { color: '#35b78d' } },
      { name: '失败', value: props.execution.failed, itemStyle: { color: '#e45b5b' } },
      { name: '阻塞', value: props.execution.blocked, itemStyle: { color: '#ef9141' } },
      { name: '未执行', value: props.execution.notRun, itemStyle: { color: '#a8b0bf' } },
    ]
    const total = data.reduce((sum, item) => sum + item.value, 0)
    executionChart.setOption({
      animationDuration: 450,
      tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条（{d}%）' },
      legend: { orient: 'vertical', right: 8, top: 'center', itemWidth: 9, itemHeight: 9, textStyle: { color: '#6f7c94', fontSize: 11 } },
      graphic: [
        { type: 'text', left: '31%', top: '43%', style: { text: String(total), textAlign: 'center', fill: '#17233d', fontSize: 25, fontWeight: 700 } },
        { type: 'text', left: '31%', top: '56%', style: { text: '执行用例', textAlign: 'center', fill: '#8a94a6', fontSize: 10 } },
      ],
      series: [{
        name: '执行结论',
        type: 'pie',
        radius: ['54%', '75%'],
        center: ['35%', '50%'],
        avoidLabelOverlap: true,
        label: { show: false },
        emphasis: { scaleSize: 5 },
        data: total ? data : [{ name: '暂无执行', value: 1, itemStyle: { color: '#edf0f5' } }],
      }],
    }, { notMerge: true })
  }
}

function resize() {
  assetChart?.resize()
  executionChart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})
watch(() => [props.assetCounts, props.execution], render, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  assetChart?.dispose()
  executionChart?.dispose()
})
</script>

<template>
  <div class="overview-charts">
    <article class="panel chart-card">
      <div class="chart-title"><div><h3>测试资产分布</h3><p>需求、用例、计划与缺陷的当前规模</p></div><a-tag color="arcoblue">实时</a-tag></div>
      <div ref="assetChartRef" class="overview-chart" role="img" aria-label="测试资产数量柱状图"></div>
    </article>
    <article class="panel chart-card">
      <div class="chart-title"><div><h3>执行结果构成</h3><p>全部执行批次的用例结论汇总</p></div><a-tag color="green">质量</a-tag></div>
      <div ref="executionChartRef" class="overview-chart" role="img" aria-label="测试执行结果饼图"></div>
    </article>
  </div>
</template>

<style scoped>
.overview-charts{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(360px,.75fr);gap:17px}.chart-card{padding:20px}.chart-title{display:flex;align-items:flex-start;justify-content:space-between}.chart-title h3{margin:0 0 5px;font-size:15px}.chart-title p{margin:0;color:var(--muted);font-size:11px}.overview-chart{width:100%;height:265px}@media(max-width:1100px){.overview-charts{grid-template-columns:1fr}.overview-chart{height:240px}}
</style>
