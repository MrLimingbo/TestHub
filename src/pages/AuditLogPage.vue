<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'
import { ApiRequestError, toApiRequestError } from '@/composables/useApiFormErrors'

interface ApiEnvelope<T> { data: T }
interface Entry { id: string; userId: string | null; projectId: string | null; action: string; resourceType: string; resourceId: string | null; outcome: string; requestId: string; clientIp: string | null; detail: Record<string, unknown>; createdAt: string }

const entries = ref<Entry[]>([])
const loading = ref(true)
const loadError = ref(''), lastLoadedAt = ref(''), loadedAction = ref('')
const action = ref('')
const actions = ref<string[]>([])
const actionsError = ref(''), actionsLoaded = ref(false)
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))
let requestVersion = 0

async function read<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: 'include' })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw toApiRequestError(response, body)
  return body.data
}
async function load() {
  const version = ++requestVersion
  loading.value = true
  loadError.value = ''; actionsError.value = ''
  const requestedAction = action.value
  const query = requestedAction ? `?action=${encodeURIComponent(requestedAction)}&limit=100` : '?limit=100'
  const [rows, metadata] = await Promise.allSettled([
    read<Entry[]>(`/api/v1/audit-logs${query}`),
    read<unknown>('/api/v1/audit-logs/actions').then(value => {
      if (!Array.isArray(value) || !value.every(item => typeof item === 'string')) throw new Error('审计动作选项格式无效')
      return [...new Set(value as string[])].sort()
    }),
  ])
  if (version !== requestVersion) return
  const denied = [rows, metadata].some(result => result.status === 'rejected' && result.reason instanceof ApiRequestError && [401, 403].includes(result.reason.status))
  if (denied) {
    entries.value = []; actions.value = []; action.value = ''; actionsLoaded.value = false
    lastLoadedAt.value = ''; loadedAction.value = ''
    loadError.value = '当前无权读取审计记录，请重新确认登录状态与权限'
    loading.value = false
    return
  }
  if (rows.status === 'fulfilled') {
    entries.value = rows.value
    lastLoadedAt.value = new Date().toISOString(); loadedAction.value = requestedAction
  } else {
    loadError.value = rows.reason instanceof Error ? rows.reason.message : '加载审计日志失败'
    Message.error(loadError.value)
  }
  if (metadata.status === 'fulfilled') {
    actions.value = metadata.value; actionsLoaded.value = true
  } else {
    const reason = metadata.reason instanceof Error ? metadata.reason.message : '请求失败'
    actionsError.value = `动作选项读取失败：${reason}。${actionsLoaded.value ? '当前保留上次动作选项（旧数据）' : '尚无可用动作选项'}`
  }
  loading.value = false
}
onMounted(load)
onBeforeUnmount(() => { requestVersion++; entries.value = []; actions.value = []; action.value = '' })
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="SECURITY OBSERVABILITY" title="权限与审计" description="记录平台关键写操作；密钥原文、令牌原文与配置值不会进入审计详情。" />
    <section class="panel audit-toolbar"><div><a-select v-model="action" allow-clear placeholder="全部动作" :style="{ width: '260px' }" @change="load"><a-option v-for="item in actions" :key="item" :value="item">{{ item }}</a-option></a-select></div><a-button @click="load"><icon-refresh /> 刷新</a-button></section>
    <a-alert v-if="actionsError" type="warning" role="alert">{{ actionsError }}<a-button type="text" :loading="loading" @click="load">重试动作选项</a-button></a-alert>
    <a-alert v-if="loadError" class="audit-load-error" type="warning" role="alert">
      {{ loadError }}。{{ lastLoadedAt ? `当前显示旧数据，来自上次成功的“${loadedAction || '全部动作'}”结果。` : '尚无成功加载的数据。' }}
      <a-button type="text" :loading="loading" @click="load">重试</a-button>
    </a-alert>
    <p v-if="lastLoadedAt" class="audit-last-success" role="status">{{ loading ? '正在刷新 · ' : '' }}上次成功：<time :datetime="lastLoadedAt">{{ date(lastLoadedAt) }}</time> · {{ loadedAction || '全部动作' }}</p>
    <section class="panel data-panel"><a-spin :loading="loading"><a-table :data="entries" :pagination="{ pageSize: 10 }" row-key="id"><template #columns><a-table-column title="时间" data-index="createdAt" :width="185"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column><a-table-column title="动作" data-index="action"><template #cell="{ record }"><a-tag color="arcoblue">{{ record.action }}</a-tag></template></a-table-column><a-table-column title="资源" data-index="resourceType"><template #cell="{ record }"><strong>{{ record.resourceType }}</strong><small class="audit-subtitle">{{ record.resourceId ?? '—' }}</small></template></a-table-column><a-table-column title="结果"><template #cell="{ record }"><a-tag :color="record.outcome === 'SUCCESS' ? 'green' : 'red'">{{ record.outcome }}</a-tag></template></a-table-column><a-table-column title="请求 ID" data-index="requestId"><template #cell="{ record }"><code class="request-id">{{ record.requestId }}</code></template></a-table-column></template></a-table></a-spin></section>
  </div>
</template>

<style scoped>
.audit-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:20px}.audit-subtitle{display:block;color:var(--color-text-3);font-size:12px;margin-top:3px}.request-id{font-size:12px;color:var(--color-text-3)}
</style>
