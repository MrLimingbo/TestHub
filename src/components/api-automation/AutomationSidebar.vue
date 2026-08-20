<script setup lang="ts">
import { computed } from 'vue'

interface ScenarioListItem {
  id: string
  name: string
  version: number
  stepCount: number
  environmentCode: string
}

const props = defineProps<{
  scenarios: ScenarioListItem[]
  selectedScenarioId?: string
  keyword: string
  loading: boolean
  activeAsset: 'steps' | 'data' | 'reports' | 'integrations'
  dataSetCount: number
  scheduleCount: number
  reportCount: number
}>()

const emit = defineEmits<{
  create: []
  refresh: []
  select: [scenarioId: string]
  delete: [scenarioId: string]
  'update:keyword': [value: string]
  'update:activeAsset': [value: 'steps' | 'data' | 'reports' | 'integrations']
}>()

const visibleScenarios = computed(() => {
  const query = props.keyword.trim().toLocaleLowerCase()
  if (!query) return props.scenarios
  return props.scenarios.filter((scenario) => `${scenario.name} ${scenario.environmentCode}`.toLocaleLowerCase().includes(query))
})
</script>

<template>
  <aside class="automation-sidebar">
    <header class="automation-sidebar__header">
      <div>
        <span>接口自动化</span>
        <small>{{ scenarios.length }} 个场景</small>
      </div>
      <a-button type="primary" shape="circle" size="small" aria-label="新建场景" @click="emit('create')">
        <icon-plus />
      </a-button>
    </header>

    <div class="automation-sidebar__search">
      <a-input
        :model-value="keyword"
        allow-clear
        placeholder="搜索场景"
        aria-label="搜索自动化场景"
        @update:model-value="emit('update:keyword', String($event))"
      >
        <template #prefix><icon-search /></template>
      </a-input>
      <a-button shape="circle" aria-label="刷新场景" @click="emit('refresh')"><icon-refresh /></a-button>
    </div>

    <nav class="automation-assets" aria-label="自动化测试资产">
      <router-link class="automation-assets__link" to="/api-testing/interfaces">
        <span class="asset-icon tone-slate"><icon-thunderbolt /></span>
        <strong>单接口用例</strong>
        <icon-right />
      </router-link>

      <section class="asset-group">
        <button
          class="asset-group__heading"
          :class="{ active: activeAsset === 'steps' }"
          @click="emit('update:activeAsset', 'steps')"
        >
          <span class="asset-icon tone-purple"><icon-code /></span>
          <strong>场景用例</strong>
          <span>{{ scenarios.length }}</span>
        </button>
        <div class="scenario-group-label"><icon-folder /> 默认分组</div>
        <a-spin :loading="loading">
          <div
            v-for="scenario in visibleScenarios"
            :key="scenario.id"
            class="scenario-tree-item"
            :class="{ selected: scenario.id === selectedScenarioId }"
          >
            <button class="scenario-tree-main" @click="emit('select', scenario.id)">
              <span class="scenario-type">TS</span>
              <span>
                <strong>{{ scenario.name }}</strong>
                <small>v{{ scenario.version }} · {{ scenario.stepCount }} 步</small>
              </span>
            </button>
            <a-popconfirm content="删除后不可恢复；已有执行、计划或 Webhook 引用的场景会被保护。确定继续？" @ok="emit('delete', scenario.id)">
              <button class="scenario-delete" :aria-label="`删除场景 ${scenario.name}`"><icon-delete /></button>
            </a-popconfirm>
          </div>
          <div v-if="!loading && !visibleScenarios.length" class="scenario-tree-empty">
            {{ keyword ? '没有匹配的场景' : '还没有场景用例' }}
          </div>
        </a-spin>
      </section>

      <button class="automation-assets__link" @click="emit('update:activeAsset', 'data')">
        <span class="asset-icon tone-cyan"><icon-storage /></span>
        <strong>测试数据</strong>
        <span class="asset-count">{{ dataSetCount }}</span>
      </button>
      <button class="automation-assets__link" @click="emit('update:activeAsset', 'integrations')">
        <span class="asset-icon tone-orange"><icon-clock-circle /></span>
        <strong>定时任务</strong>
        <span class="asset-count">{{ scheduleCount }}</span>
      </button>
      <button class="automation-assets__link" @click="emit('update:activeAsset', 'reports')">
        <span class="asset-icon tone-green"><icon-bar-chart /></span>
        <strong>测试报告</strong>
        <span class="asset-count">{{ reportCount }}</span>
      </button>
    </nav>

    <footer class="automation-sidebar__footer">
      <span><icon-safe /> 项目级隔离</span>
      <small>所有执行均由独立 Runner 完成</small>
    </footer>
  </aside>
</template>

<style scoped>
.automation-sidebar{display:flex;flex-direction:column;min-width:0;min-height:0;background:#fbfcff;border-right:1px solid #e7eaf1}.automation-sidebar__header{display:flex;align-items:center;justify-content:space-between;height:62px;padding:0 16px;border-bottom:1px solid #eceef4}.automation-sidebar__header>div{display:grid;gap:2px}.automation-sidebar__header span{font-size:16px;font-weight:700;color:#1d2738}.automation-sidebar__header small{font-size:10px;color:#8b95a7}.automation-sidebar__search{display:grid;grid-template-columns:minmax(0,1fr) 32px;gap:7px;padding:12px}.automation-assets{display:grid;align-content:start;gap:4px;padding:0 9px 14px;overflow:auto}.automation-assets__link,.asset-group__heading{display:grid;grid-template-columns:28px minmax(0,1fr) auto;align-items:center;gap:9px;width:100%;min-height:40px;padding:6px 9px;color:#556176;text-align:left;text-decoration:none;border:0;border-radius:8px;background:transparent;cursor:pointer}.automation-assets__link:hover,.asset-group__heading:hover,.asset-group__heading.active{color:#5035c8;background:#f1efff}.automation-assets__link strong,.asset-group__heading strong{font-size:12px;font-weight:600}.asset-icon{display:grid;place-items:center;width:27px;height:27px;border-radius:7px;font-size:14px}.tone-slate{color:#60708b;background:#edf0f5}.tone-purple{color:#6d4ce6;background:#eeeaff}.tone-cyan{color:#158ca8;background:#e7f8fb}.tone-orange{color:#c87524;background:#fff0e1}.tone-green{color:#168565;background:#e6f7f1}.asset-count,.asset-group__heading>span:last-child{min-width:21px;padding:2px 6px;color:#8791a3;font-size:10px;text-align:center;border-radius:10px;background:#eef1f6}.asset-group{display:grid;gap:3px}.scenario-group-label{display:flex;align-items:center;gap:7px;margin:2px 0 2px 39px;color:#707c90;font-size:11px}.scenario-tree-item{display:grid;grid-template-columns:minmax(0,1fr) 24px;align-items:center;width:100%;padding-left:29px;color:#48546a;border-radius:8px}.scenario-tree-item:hover,.scenario-tree-item.selected{background:#efeff8}.scenario-tree-item.selected{color:#3f2dae}.scenario-tree-main{display:grid;grid-template-columns:26px minmax(0,1fr);align-items:center;gap:8px;min-width:0;padding:7px 0;color:inherit;text-align:left;border:0;background:transparent;cursor:pointer}.scenario-tree-main>span:last-child{display:grid;min-width:0;gap:2px}.scenario-tree-item strong,.scenario-tree-item small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scenario-tree-item strong{font-size:11px}.scenario-tree-item small{color:#97a0af;font-size:9px}.scenario-delete{display:grid;place-items:center;width:22px;height:22px;color:#a2aaba;border:0;border-radius:6px;background:transparent;opacity:0;cursor:pointer}.scenario-tree-item:hover .scenario-delete,.scenario-tree-item:focus-within .scenario-delete{opacity:1}.scenario-delete:hover{color:#d34d4d;background:#fff}.scenario-type{display:grid;place-items:center;width:24px;height:24px;color:#fff;font-size:8px;font-weight:700;border-radius:7px;background:linear-gradient(145deg,#66a8ff,#6e6bea)}.scenario-tree-empty{padding:14px 10px 14px 39px;color:#9aa3b2;font-size:10px}.automation-sidebar__footer{display:grid;gap:3px;margin-top:auto;padding:13px 16px;color:#7b879a;border-top:1px solid #eceef4;background:#fff}.automation-sidebar__footer span{display:flex;align-items:center;gap:6px;font-size:10px;font-weight:600}.automation-sidebar__footer small{font-size:9px;color:#a1a9b7}
</style>
