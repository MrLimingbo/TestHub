<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Entry { id: string; userId: string | null; projectId: string | null; action: string; resourceType: string; resourceId: string | null; outcome: string; requestId: string; clientIp: string | null; detail: Record<string, unknown>; createdAt: string }

const entries = ref<Entry[]>([])
const loading = ref(true)
const action = ref('')
const actions = computed(() => [...new Set(entries.value.map((entry) => entry.action))])
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))

async function load() {
  loading.value = true
  try {
    const query = action.value ? `?action=${encodeURIComponent(action.value)}&limit=100` : '?limit=100'
    const response = await fetch(`/api/v1/audit-logs${query}`, { credentials: 'include' })
    const body = await response.json().catch(() => ({})) as ApiEnvelope<Entry[]> & { message?: string }
    if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
    entries.value = body.data
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载审计日志失败') } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="SECURITY OBSERVABILITY" title="权限与审计" description="记录平台关键写操作；密钥原文、令牌原文与配置值不会进入审计详情。" />
    <section class="panel audit-toolbar"><div><a-select v-model="action" allow-clear placeholder="全部动作" :style="{ width: '260px' }" @change="load"><a-option v-for="item in actions" :key="item" :value="item">{{ item }}</a-option></a-select></div><a-button @click="load"><icon-refresh /> 刷新</a-button></section>
    <section class="panel data-panel"><a-spin :loading="loading"><a-table :data="entries" :pagination="{ pageSize: 10 }" row-key="id"><template #columns><a-table-column title="时间" data-index="createdAt" :width="185"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column><a-table-column title="动作" data-index="action"><template #cell="{ record }"><a-tag color="arcoblue">{{ record.action }}</a-tag></template></a-table-column><a-table-column title="资源" data-index="resourceType"><template #cell="{ record }"><strong>{{ record.resourceType }}</strong><small class="audit-subtitle">{{ record.resourceId ?? '—' }}</small></template></a-table-column><a-table-column title="结果"><template #cell="{ record }"><a-tag :color="record.outcome === 'SUCCESS' ? 'green' : 'red'">{{ record.outcome }}</a-tag></template></a-table-column><a-table-column title="请求 ID" data-index="requestId"><template #cell="{ record }"><code class="request-id">{{ record.requestId }}</code></template></a-table-column></template></a-table></a-spin></section>
  </div>
</template>

<style scoped>
.audit-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:20px}.audit-subtitle{display:block;color:var(--color-text-3);font-size:12px;margin-top:3px}.request-id{font-size:12px;color:var(--color-text-3)}
</style>
