<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

interface ApiEnvelope<T> { data: T }
interface RuleSummary { name: string; httpMethod: string; path: string; responseStatus: number; matchSummary: string }
interface ShareDocument { shareKey: string; shareName: string; serviceName: string; publicKey: string; serviceEnabled: boolean; smartExampleEnabled: boolean; expiresAt: string | null; rules: RuleSummary[] }

const route = useRoute()
const document = ref<ShareDocument | null>(null)
const loading = ref(true)
const error = ref('')
const runtimeUrl = computed(() => document.value ? `${window.location.origin}/api/v1/mock/${document.value.publicKey}` : '')

function shareKey() { return String(route.params.shareKey ?? '') }
function date(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '永不过期' }
function statusColor(status: number) { return status >= 500 ? 'red' : status >= 400 ? 'orange' : status >= 300 ? 'arcoblue' : 'green' }

async function load() {
  loading.value = true
  error.value = ''
  document.value = null
  try {
    const response = await fetch(`/api/v1/mock-shares/${encodeURIComponent(shareKey())}`)
    const body = await response.json().catch(() => ({})) as ApiEnvelope<ShareDocument> & { message?: string }
    if (!response.ok) throw new Error(body.message ?? '分享链接不可用')
    document.value = body.data
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '分享链接不可用'
  } finally {
    loading.value = false
  }
}

async function copyRuntimeUrl() {
  if (!runtimeUrl.value || !navigator.clipboard) return
  await navigator.clipboard.writeText(runtimeUrl.value)
}

onMounted(load)
watch(() => route.params.shareKey, load)
</script>

<template>
  <main class="share-page">
    <section class="share-card">
      <header class="share-header"><div><span>TESTHUB · MOCK SHARE</span><h1>Mock 调用说明</h1></div><a-tag color="arcoblue">只读分享</a-tag></header>
      <a-spin :loading="loading" class="share-content">
        <a-result v-if="error" status="error" title="分享链接不可用" :subtitle="error" />
        <template v-else-if="document">
          <section class="intro"><div><span class="eyebrow">SHARED GUIDE</span><h2>{{ document.shareName }}</h2><p>服务：{{ document.serviceName }}。此页面仅展示调用说明，不包含响应正文、项目密钥或成员信息。</p></div><a-space><a-tag :color="document.serviceEnabled ? 'green' : 'gray'">{{ document.serviceEnabled ? '服务已启用' : '服务已停用' }}</a-tag><a-tag :color="document.smartExampleEnabled ? 'purple' : 'gray'">{{ document.smartExampleEnabled ? '智能示例已启用' : '仅手动规则' }}</a-tag></a-space></section>
          <section class="runtime"><div><strong>公开调用地址</strong><small>无需登录；服务停用或规则未命中时会返回 404。</small></div><code>{{ runtimeUrl }}</code><a-button size="small" @click="copyRuntimeUrl">复制</a-button></section>
          <section class="meta"><div><span>链接有效期</span><strong>{{ date(document.expiresAt) }}</strong></div><div><span>可调用规则</span><strong>{{ document.rules.length }}</strong></div><div><span>调用边界</span><strong>仅公开地址</strong></div></section>
          <section class="rules"><div class="section-heading"><div><h3>规则概览</h3><p>条件仅显示类型和数量；请向服务维护者索取测试数据与响应契约。</p></div></div><a-table :data="document.rules" :pagination="false" row-key="name"><template #columns><a-table-column title="规则"><template #cell="{ record }"><strong>{{ record.name }}</strong><small>{{ record.matchSummary }}</small></template></a-table-column><a-table-column title="请求"><template #cell="{ record }"><a-tag :color="record.httpMethod === 'GET' ? 'green' : 'arcoblue'">{{ record.httpMethod }}</a-tag> <code>{{ record.path }}</code></template></a-table-column><a-table-column title="响应" :width="110"><template #cell="{ record }"><a-tag :color="statusColor(record.responseStatus)">{{ record.responseStatus }}</a-tag></template></a-table-column></template></a-table><a-empty v-if="!document.rules.length" description="此服务当前没有可公开说明的启用规则" /></section>
        </template>
      </a-spin>
    </section>
  </main>
</template>

<style scoped>
.share-page{display:grid;min-height:100vh;padding:48px 20px;background:linear-gradient(150deg,#edf5ff,#f7f9fc 46%,#e9f7f1);place-items:start center}.share-card{width:min(960px,100%);padding:28px;border:1px solid rgba(22,93,255,.14);border-radius:16px;background:#fff;box-shadow:0 20px 60px rgba(25,55,90,.1)}.share-header,.intro,.runtime,.section-heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.share-header{align-items:center;padding-bottom:22px;border-bottom:1px solid var(--color-border-2)}.share-header span,.eyebrow,.intro p,.runtime small,.meta span,.section-heading p,.rules small{color:var(--color-text-3);font-size:12px}.share-header h1,.intro h2,.section-heading h3{margin:5px 0 0}.share-content{display:block;min-height:240px}.intro{padding:24px 0}.intro>div{min-width:0}.intro p{max-width:620px;margin:8px 0 0;line-height:1.65}.runtime{align-items:center;padding:14px;border:1px solid rgb(var(--primary-2));border-radius:8px;background:rgb(var(--primary-1))}.runtime>div{display:grid;gap:4px}.runtime code{min-width:0;max-width:54%;overflow:hidden;color:rgb(var(--primary-6));text-overflow:ellipsis;white-space:nowrap;font-size:12px}.meta{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:18px 0}.meta>div{display:grid;gap:5px;padding:12px;border:1px solid var(--color-border-2);border-radius:8px}.meta strong{font-size:14px}.rules{padding-top:8px}.section-heading{margin-bottom:14px}.section-heading h3{font-size:16px}.section-heading p{margin:5px 0 0;line-height:1.5}.rules small{display:block;margin-top:4px}@media (max-width:680px){.share-page{padding:20px 12px}.share-card{padding:18px}.share-header,.intro,.runtime{flex-direction:column;align-items:stretch}.runtime code{max-width:100%;white-space:normal;word-break:break-all}.meta{grid-template-columns:1fr}.intro :deep(.arco-space){justify-content:flex-start}}
</style>
