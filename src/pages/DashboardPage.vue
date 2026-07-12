<script setup lang="ts">
import ChartPanel from '@/components/ChartPanel.vue'
import MetricGrid from '@/components/MetricGrid.vue'

const metrics = [
  { label: '测试用例', value: '1,286', delta: '+8.6%', tone: 'blue' as const },
  { label: '接口资产', value: '438', delta: '+24', tone: 'green' as const },
  { label: '自动化场景', value: '96', delta: '+12', tone: 'violet' as const },
  { label: '待解决缺陷', value: '23', delta: '-5', tone: 'orange' as const },
]
const activity = [
  { title: '支付回归场景执行完成', meta: '接口自动化 · 128/128 通过', time: '8 分钟前', tone: 'success' },
  { title: '交易核心 P0 用例评审', meta: '功能测试 · 18 项变更', time: '32 分钟前', tone: 'primary' },
  { title: '峰值 5k TPS 压测结束', meta: '性能测试 · P95 186ms', time: '1 小时前', tone: 'violet' },
  { title: '订单查询接口有新版本', meta: '接口设计 · v2.7', time: '昨天', tone: 'warning' },
]
</script>

<template>
  <div class="page dashboard-page">
    <section class="welcome-banner">
      <div><div class="eyebrow">2026 年 7 月 11 日 · 星期六</div><h1>早上好，林晓</h1><p>交易中台 V3 本周质量趋势稳定，还有 3 个高优缺陷等待处理。</p></div>
      <div class="quality-score"><span>项目质量分</span><strong>92</strong><small>较上周 +4</small></div>
    </section>
    <MetricGrid :metrics="metrics" />
    <section class="dashboard-grid">
      <article class="panel panel--wide">
        <div class="panel-title"><div><h3>质量趋势</h3><p>近 7 天执行量与通过量</p></div><a-radio-group default-value="week" type="button" size="small"><a-radio value="week">本周</a-radio><a-radio value="month">本月</a-radio></a-radio-group></div>
        <ChartPanel />
      </article>
      <article class="panel">
        <div class="panel-title"><div><h3>执行分布</h3><p>按测试类型统计</p></div><a-button type="text">查看报告</a-button></div>
        <div class="donut-wrap">
          <div class="donut"><div><strong>2,418</strong><span>总执行</span></div></div>
          <ul class="legend"><li><i class="blue"></i><span>接口自动化</span><strong>46%</strong></li><li><i class="green"></i><span>功能测试</span><strong>31%</strong></li><li><i class="violet"></i><span>性能测试</span><strong>15%</strong></li><li><i class="orange"></i><span>UI 自动化</span><strong>8%</strong></li></ul>
        </div>
      </article>
      <article class="panel">
        <div class="panel-title"><div><h3>最近动态</h3><p>项目内关键质量活动</p></div><a-button type="text">全部动态</a-button></div>
        <div class="activity-list"><div v-for="item in activity" :key="item.title" class="activity-item"><span class="activity-icon" :class="item.tone"></span><div><strong>{{ item.title }}</strong><p>{{ item.meta }}</p></div><time>{{ item.time }}</time></div></div>
      </article>
      <article class="panel">
        <div class="panel-title"><div><h3>快捷开始</h3><p>创建常用测试资产</p></div></div>
        <div class="quick-grid"><router-link to="/functional"><icon-safe />新建用例</router-link><router-link to="/api-debug"><icon-thunderbolt />调试接口</router-link><router-link to="/api-automation"><icon-robot />编排场景</router-link><router-link to="/performance"><icon-fire />发起压测</router-link></div>
      </article>
    </section>
  </div>
</template>
