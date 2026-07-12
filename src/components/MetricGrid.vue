<script setup lang="ts">
import type { MetricCard } from '@/types/platform'
defineProps<{ metrics: MetricCard[] }>()
</script>

<template>
  <section class="metric-grid" aria-label="关键指标">
    <article v-for="metric in metrics" :key="metric.label" class="metric-card" :class="`tone-${metric.tone}`">
      <div class="metric-card__top"><span>{{ metric.label }}</span><span class="metric-dot"></span></div>
      <strong>{{ metric.value }}</strong>
      <div class="metric-card__footer"><span>{{ metric.delta }}</span><small>较上周期</small></div>
    </article>
  </section>
</template>
