<script setup lang="ts">
import { ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import ChartPanel from '@/components/ChartPanel.vue'
import MetricGrid from '@/components/MetricGrid.vue'
import PageHeader from '@/components/PageHeader.vue'

const running = ref(false)
const metrics = [
  { label: '当前 TPS', value: '1,250', delta: '峰值 1,486', tone: 'blue' as const },
  { label: '平均响应', value: '186ms', delta: 'P95 320ms', tone: 'green' as const },
  { label: '错误率', value: '0.35%', delta: '阈值 < 1%', tone: 'orange' as const },
  { label: '活跃线程', value: '500', delta: '目标 500', tone: 'violet' as const },
]
const start = async () => { running.value = true; Message.info('压测任务已进入队列'); await new Promise((resolve) => setTimeout(resolve, 1200)); running.value = false; Message.success('演示任务已完成') }
</script>

<template><div class="page"><PageHeader eyebrow="JMeter Runner" title="性能测试" description="统一编排负载模型，实时观察吞吐、延迟与错误趋势。" action="发起压测" @action="start" /><MetricGrid :metrics="metrics" /><section class="dashboard-grid performance-grid"><article class="panel panel--wide"><div class="panel-title"><div><h3>实时性能趋势</h3><p>交易链路基准测试 · 运行中 08:42</p></div><div class="live-state"><i></i>实时</div></div><ChartPanel /></article><article class="panel"><div class="panel-title"><div><h3>运行配置</h3><p>阶梯加压模型</p></div><a-button type="text">编辑</a-button></div><div class="config-list"><div><span>并发用户</span><strong>500</strong></div><div><span>加压时长</span><strong>5 分钟</strong></div><div><span>持续时间</span><strong>30 分钟</strong></div><div><span>执行节点</span><strong>2 Runner</strong></div></div><a-button type="primary" long :loading="running" @click="start">{{ running ? '运行中' : '重新执行' }}</a-button></article><article class="panel panel--full"><div class="panel-title"><div><h3>场景任务</h3><p>最近执行记录与质量阈值</p></div><a-button @click="Message.success('报告已导出')"><icon-download /> 导出报告</a-button></div><a-table :pagination="false" :columns="[{ title: '场景', dataIndex: 'name' }, { title: '并发', dataIndex: 'threads' }, { title: 'TPS', dataIndex: 'tps' }, { title: 'P95', dataIndex: 'p95' }, { title: '错误率', dataIndex: 'errors' }, { title: '状态', dataIndex: 'status' }]" :data="[{ name: '交易核心链路基准', threads: 500, tps: '1,486', p95: '320ms', errors: '0.35%', status: '通过' }, { name: '订单查询容量验证', threads: 300, tps: '2,106', p95: '186ms', errors: '0.08%', status: '通过' }, { name: '营销活动峰值验证', threads: 800, tps: '986', p95: '680ms', errors: '1.24%', status: '未通过' }]" /></article></section></div></template>
