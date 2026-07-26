<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface Envelope<T> { data: T }
interface ComponentStatus { key: string; label: string; status: 'UP' | 'DOWN'; detail: string }
interface Control { key: string; label: string; status: 'ENABLED' | 'ATTENTION'; detail: string }
interface DataProtection { migrationVersion: string; migrationStatus: 'UP' | 'ATTENTION'; migrationDetail: string; controls: Control[] }
interface CapacityProfile { status: 'ENABLED' | 'ATTENTION'; summary: string; controls: Control[] }
interface Overview { status: 'UP' | 'ATTENTION'; checkedAt: string; components: ComponentStatus[]; controls: Control[]; dataProtection: DataProtection; capacity: CapacityProfile }

const overview = ref<Overview | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const checkedAt = computed(() => overview.value?.checkedAt ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(overview.value.checkedAt)) : '—')
const healthyComponents = computed(() => overview.value?.components.filter(item => item.status === 'UP').length ?? 0)
const attentionControls = computed(() => overview.value?.controls.filter(item => item.status === 'ATTENTION').length ?? 0)
const protectionAttention = computed(() => overview.value?.dataProtection.controls.filter(item => item.status === 'ATTENTION').length ?? 0)
const capacityEnabled = computed(() => overview.value?.capacity.controls.filter(item => item.status === 'ENABLED').length ?? 0)

async function load() {
  loading.value = true; errorMessage.value = ''
  try {
    const response = await fetch('/api/v1/operations/overview', { credentials: 'include' })
    const body = await response.json().catch(() => ({})) as Envelope<Overview> & { message?: string }
    if (!response.ok) throw new Error(body.message ?? '运行状态读取失败')
    overview.value = body.data
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '运行状态读取失败'
    Message.error(errorMessage.value)
  } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="PRODUCTION READINESS" title="运行与发布" description="系统管理员可查看不含密钥与内部地址的基础依赖状态、安全控制与生产部署就绪项。" action="刷新检查" @action="load" />
    <a-alert v-if="errorMessage" type="warning" :show-icon="true" class="access-alert">{{ errorMessage }}。运行与发布中心仅向系统管理员开放。</a-alert>
    <a-spin :loading="loading">
      <template v-if="overview">
        <section class="panel readiness-hero" :class="overview.status === 'UP' ? 'is-up' : 'is-attention'"><div><span class="eyebrow">CONTROL PLANE</span><h2>{{ overview.status === 'UP' ? '基础依赖已就绪' : '发现需要处理的运行项' }}</h2><p>最后检查：{{ checkedAt }}。外部地址、密钥、数据库连接串与错误堆栈均不会在此页面显示。</p></div><a-tag :color="overview.status === 'UP' ? 'green' : 'orange'" size="large">{{ overview.status === 'UP' ? 'UP' : 'ATTENTION' }}</a-tag></section>
        <section class="metric-row"><article class="panel metric"><span>基础依赖</span><strong>{{ healthyComponents }}/{{ overview.components.length }}</strong><small>MySQL、Redis、MinIO 连通性</small></article><article class="panel metric"><span>安全控制</span><strong>{{ overview.controls.length - attentionControls }}/{{ overview.controls.length }}</strong><small>当前已启用的运行安全项</small></article><article class="panel metric"><span>容量控制</span><strong>{{ capacityEnabled }}/{{ overview.capacity.controls.length }}</strong><small>基线、告警与恢复演练</small></article><article class="panel metric"><span>需处理项</span><strong>{{ attentionControls + protectionAttention + (overview.capacity.status === 'ATTENTION' ? 1 : 0) }}</strong><small>外部证据与隔离演练要求</small></article></section>
        <section class="status-grid"><section class="panel status-panel"><div class="section-title"><div><span class="eyebrow">DEPENDENCIES</span><h3>运行依赖</h3></div><small>详情仅在受保护日志与指标系统中查看</small></div><article v-for="component in overview.components" :key="component.key" class="status-row"><span class="status-icon" :class="component.status === 'UP' ? 'up' : 'down'"><icon-check v-if="component.status === 'UP'" /><icon-close v-else /></span><div><strong>{{ component.label }}</strong><small>{{ component.detail }}</small></div><a-tag :color="component.status === 'UP' ? 'green' : 'red'">{{ component.status }}</a-tag></article></section><section class="panel status-panel"><div class="section-title"><div><span class="eyebrow">SECURITY & OBSERVABILITY</span><h3>发布控制</h3></div><small>Prometheus 仅应置于内部网络</small></div><article v-for="control in overview.controls" :key="control.key" class="status-row"><span class="status-icon" :class="control.status === 'ENABLED' ? 'up' : 'attention'"><icon-check v-if="control.status === 'ENABLED'" /><icon-exclamation v-else /></span><div><strong>{{ control.label }}</strong><small>{{ control.detail }}</small></div><a-tag :color="control.status === 'ENABLED' ? 'green' : 'orange'">{{ control.status }}</a-tag></article></section></section>
        <section class="panel protection-panel"><div class="section-title"><div><span class="eyebrow">DATA PROTECTION</span><h3>备份、恢复与迁移</h3></div><a-tag :color="overview.dataProtection.migrationStatus === 'UP' ? 'green' : 'orange'">{{ overview.dataProtection.migrationVersion }}</a-tag></div><p class="migration-detail">{{ overview.dataProtection.migrationDetail }}</p><article v-for="control in overview.dataProtection.controls" :key="control.key" class="status-row"><span class="status-icon" :class="control.status === 'ENABLED' ? 'up' : 'attention'"><icon-check v-if="control.status === 'ENABLED'" /><icon-exclamation v-else /></span><div><strong>{{ control.label }}</strong><small>{{ control.detail }}</small></div><a-tag :color="control.status === 'ENABLED' ? 'green' : 'orange'">{{ control.status }}</a-tag></article><a-alert v-if="protectionAttention" type="warning" :show-icon="true" class="backup-alert">备份脚本与恢复防护已就绪，但备份产物必须由部署侧定时执行并完成完整性校验。</a-alert></section>
        <section class="panel capacity-panel"><div class="section-title"><div><span class="eyebrow">CAPACITY & RESILIENCE</span><h3>容量基准与恢复演练</h3></div><a-tag :color="overview.capacity.status === 'ENABLED' ? 'green' : 'orange'">{{ overview.capacity.status }}</a-tag></div><p class="migration-detail">{{ overview.capacity.summary }}</p><article v-for="control in overview.capacity.controls" :key="control.key" class="status-row"><span class="status-icon" :class="control.status === 'ENABLED' ? 'up' : 'attention'"><icon-check v-if="control.status === 'ENABLED'" /><icon-exclamation v-else /></span><div><strong>{{ control.label }}</strong><small>{{ control.detail }}</small></div><a-tag :color="control.status === 'ENABLED' ? 'green' : 'orange'">{{ control.status }}</a-tag></article><a-alert type="info" :show-icon="true" class="backup-alert">容量基线只对控制面就绪探针生效；业务负载、Runner 容量和灾难恢复均须在对应环境独立验收。</a-alert></section>
        <section class="panel release-note"><icon-info-circle-fill /><div><strong>最终发布边界</strong><p>本地发布验收门禁已汇总测试、镜像供应链、Compose、Helm、告警规则、容量基线和恢复前置检查。真实 Kubernetes 部署、外部业务压测、Runner 断连和数据恢复演练必须在独立审批的环境中执行。</p></div></section>
      </template>
    </a-spin>
  </div>
</template>

<style scoped>
.access-alert{margin-bottom:18px}.readiness-hero{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:18px;padding:24px}.readiness-hero.is-up{background:linear-gradient(120deg,#edfff4,#fff)}.readiness-hero.is-attention{background:linear-gradient(120deg,#fff7e8,#fff)}.readiness-hero h2{margin:5px 0 7px;font-size:22px}.readiness-hero p{margin:0;color:var(--color-text-3);font-size:13px;line-height:1.6}.metric-row{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:18px}.metric{display:grid;gap:5px;padding:18px}.metric span,.metric small{color:var(--color-text-3);font-size:12px}.metric strong{font-size:28px}.status-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}.status-panel,.protection-panel,.capacity-panel{padding:18px}.protection-panel,.capacity-panel{margin-top:18px}.section-title{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:12px}.section-title h3{margin:4px 0 0;font-size:17px}.section-title small{max-width:155px;color:var(--color-text-3);font-size:12px;line-height:1.5;text-align:right}.status-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:10px;padding:11px 0;border-top:1px solid var(--color-border-2)}.status-row:first-of-type{border-top:0}.status-row>div{display:grid;gap:3px}.status-row strong{font-size:13px}.status-row small,.migration-detail{color:var(--color-text-3);font-size:12px;line-height:1.5}.migration-detail{margin:0 0 10px}.status-icon{display:grid;place-items:center;width:28px;height:28px;border-radius:50%}.status-icon.up{background:#e8ffea;color:#1a9d46}.status-icon.down{background:#ffece8;color:#e54545}.status-icon.attention{background:#fff3d7;color:#d47b00}.backup-alert{margin-top:14px}.release-note{display:flex;gap:12px;align-items:flex-start;margin-top:18px;background:rgb(var(--primary-1))}.release-note svg{margin-top:2px;color:rgb(var(--primary-6));font-size:18px}.release-note strong{display:block;margin-bottom:4px}.release-note p{margin:0;color:var(--color-text-3);font-size:13px;line-height:1.6}@media (max-width:1100px){.metric-row{grid-template-columns:repeat(2,minmax(0,1fr))}}@media (max-width:900px){.metric-row,.status-grid{grid-template-columns:1fr}.section-title small{text-align:left}.readiness-hero{align-items:flex-start;flex-direction:column}}
</style>
