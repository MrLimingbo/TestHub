<script setup lang="ts">
import { computed, onMounted, provide, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRoute } from 'vue-router'
import {
  functionalWorkspaceKey,
  type FunctionalCase,
  type FunctionalCatalog,
  type FunctionalDefectSummary,
  type FunctionalRequirement,
} from '@/composables/useFunctionalWorkspace'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface FlatCatalog extends FunctionalCatalog { depth: number }
type CatalogScope = FunctionalCatalog['scope']

const route = useRoute()
const session = useSessionStore()
const catalogs = ref<FunctionalCatalog[]>([])
const requirements = ref<FunctionalRequirement[]>([])
const cases = ref<FunctionalCase[]>([])
const defects = ref<FunctionalDefectSummary[]>([])
const selectedCatalogId = ref('')
const catalogKeyword = ref('')
const loading = ref(false)
const saving = ref(false)
const catalogDialog = ref(false)
const editingCatalogId = ref<string | null>(null)
const catalogForm = reactive({ name: '', parentId: '', sortOrder: 0 })
const catalogErrors = reactive<Record<string, string>>({})

const tabs = [
  { path: '/functional', label: '汇总看板', icon: 'IconDashboard' },
  { path: '/functional/requirements', label: '需求库', icon: 'IconFile' },
  { path: '/functional/cases', label: '用例库', icon: 'IconCheckCircle' },
  { path: '/functional/plans', label: '测试计划与执行', icon: 'IconCalendar' },
  { path: '/functional/governance', label: '资产治理', icon: 'IconSettings' },
  { path: '/functional/delivery', label: '缺陷与交换', icon: 'IconBug' },
  { path: '/functional/versions', label: '版本管理', icon: 'IconBranch' },
]

const isOverview = computed(() => route.path === '/functional')
const isVersionManagement = computed(() => route.path === '/functional/versions')
const catalogScope = computed<CatalogScope>(() => {
  if (route.path.endsWith('/requirements')) return 'REQUIREMENTS'
  if (route.path.endsWith('/cases')) return 'CASES'
  if (route.path.endsWith('/plans')) return 'PLANS'
  if (route.path.endsWith('/governance')) return 'GOVERNANCE'
  return 'DELIVERY'
})
const selectedCatalogName = computed(() => catalogs.value.find((item) => item.id === selectedCatalogId.value)?.name ?? '全部目录')
const flatCatalogs = computed<FlatCatalog[]>(() => {
  const result: FlatCatalog[] = []
  const visit = (parentId: string | null, depth: number) => {
    catalogs.value
      .filter((catalog) => catalog.parentId === parentId)
      .sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name))
      .forEach((catalog) => {
        result.push({ ...catalog, depth })
        visit(catalog.id, depth + 1)
      })
  }
  visit(null, 0)
  return result
})
const visibleCatalogs = computed(() => {
  const keyword = catalogKeyword.value.trim().toLowerCase()
  if (!keyword) return flatCatalogs.value
  return flatCatalogs.value.filter((catalog) => catalog.name.toLowerCase().includes(keyword))
})
const assetTotal = computed(() => {
  if (catalogScope.value === 'REQUIREMENTS') return requirements.value.length
  if (catalogScope.value === 'CASES') return cases.value.length
  if (catalogScope.value === 'DELIVERY') return defects.value.length
  return 0
})

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { code?: string; message?: string; details?: Record<string, unknown> }
  if (!response.ok) throw toApiRequestError(response, body)
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function reload() {
  if (!session.projectId || isOverview.value || isVersionManagement.value) return
  loading.value = true
  try {
    const [catalogData, requirementData, caseData, defectData] = await Promise.all([
      read<FunctionalCatalog[]>(`/api/v1/projects/${session.projectId}/functional/catalogs?scope=${catalogScope.value}`),
      read<FunctionalRequirement[]>(`/api/v1/projects/${session.projectId}/functional/requirements`),
      read<FunctionalCase[]>(`/api/v1/projects/${session.projectId}/functional/cases`),
      read<FunctionalDefectSummary[]>(`/api/v1/projects/${session.projectId}/functional/defects`),
    ])
    catalogs.value = catalogData
    requirements.value = requirementData
    cases.value = caseData
    defects.value = defectData
    if (selectedCatalogId.value && !catalogData.some((catalog) => catalog.id === selectedCatalogId.value)) selectedCatalogId.value = ''
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载资产目录失败')
  } finally {
    loading.value = false
  }
}

function openCatalog(parentId = '', catalog?: FunctionalCatalog) {
  clearFormErrors(catalogErrors)
  editingCatalogId.value = catalog?.id ?? null
  Object.assign(catalogForm, {
    name: catalog?.name ?? '',
    parentId: catalog?.parentId ?? parentId,
    sortOrder: catalog?.sortOrder ?? 0,
  })
  catalogDialog.value = true
}

async function saveCatalog(): Promise<boolean> {
  if (!catalogForm.name.trim()) {
    catalogErrors.name = '请输入目录名称'
    return false
  }
  saving.value = true
  try {
    const url = editingCatalogId.value
      ? `/api/v1/projects/${session.projectId}/functional/catalogs/${editingCatalogId.value}`
      : `/api/v1/projects/${session.projectId}/functional/catalogs`
    await mutate(url, editingCatalogId.value ? 'PUT' : 'POST', {
      name: catalogForm.name,
      parentId: catalogForm.parentId || null,
      sortOrder: catalogForm.sortOrder,
      scope: catalogScope.value,
    })
    catalogDialog.value = false
    await reload()
    Message.success(editingCatalogId.value ? '目录已更新' : '目录已创建')
    return true
  } catch (error) {
    clearFormErrors(catalogErrors)
    Object.assign(catalogErrors, formErrorsFrom(error))
    if (Object.keys(catalogErrors).length === 0) Message.error(error instanceof Error ? error.message : '保存目录失败')
    return false
  } finally {
    saving.value = false
  }
}

async function deleteCatalog(catalog: FunctionalCatalog) {
  try {
    await mutate(`/api/v1/projects/${session.projectId}/functional/catalogs/${catalog.id}`, 'DELETE')
    if (selectedCatalogId.value === catalog.id) selectedCatalogId.value = ''
    await reload()
    Message.success('目录已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除目录失败')
  }
}

function directAssetCount(catalogId: string) {
  if (catalogScope.value === 'REQUIREMENTS') return requirements.value.filter((item) => item.catalogId === catalogId).length
  if (catalogScope.value === 'CASES') return cases.value.filter((item) => item.catalogId === catalogId).length
  if (catalogScope.value === 'DELIVERY') return defects.value.filter((item) => item.catalogId === catalogId).length
  return 0
}

provide(functionalWorkspaceKey, {
  catalogs,
  requirements,
  cases,
  defects,
  selectedCatalogId,
  selectedCatalogName,
  loading,
  reload,
})

watch([() => session.projectId, catalogScope, isVersionManagement], () => {
  selectedCatalogId.value = ''
  void reload()
})

onMounted(reload)
</script>

<template>
  <div class="page functional-shell">
    <nav class="functional-tabs" aria-label="功能测试模块导航">
      <router-link v-for="tab in tabs" :key="tab.path" :to="tab.path" class="functional-tab">
        <component :is="tab.icon" />
        <span>{{ tab.label }}</span>
      </router-link>
    </nav>

    <div v-if="isOverview" class="functional-overview">
      <router-view />
    </div>

    <section v-else-if="!isVersionManagement" class="functional-workspace">
      <aside class="asset-catalog panel">
        <div class="catalog-title">
          <div>
            <span>ASSET CATALOG</span>
            <h2>资产目录</h2>
          </div>
          <a-tooltip content="新建一级目录">
            <a-button type="primary" shape="circle" size="small" aria-label="新建一级目录" @click="openCatalog()"><icon-plus /></a-button>
          </a-tooltip>
        </div>
        <a-input-search v-model="catalogKeyword" allow-clear placeholder="搜索资产目录" />
        <a-spin class="catalog-spin" :loading="loading">
          <button class="catalog-row catalog-all" :class="{ selected: !selectedCatalogId }" @click="selectedCatalogId = ''">
            <icon-apps />
            <span>全部资产</span>
            <small>{{ assetTotal }}</small>
          </button>
          <div class="catalog-list">
            <div v-for="catalog in visibleCatalogs" :key="catalog.id" class="catalog-entry">
              <button
                class="catalog-row"
                :class="{ selected: selectedCatalogId === catalog.id }"
                :style="{ paddingLeft: `${12 + catalog.depth * 18}px` }"
                @click="selectedCatalogId = catalog.id"
              >
                <icon-folder />
                <span>{{ catalog.name }}</span>
                <small>{{ directAssetCount(catalog.id) }}</small>
              </button>
              <a-dropdown trigger="click">
                <a-button class="catalog-more" type="text" size="mini" aria-label="目录操作" @click.stop><icon-more /></a-button>
                <template #content>
                  <a-doption @click="openCatalog(catalog.id)"><icon-folder-add /> 新建子目录</a-doption>
                  <a-doption @click="openCatalog('', catalog)"><icon-edit /> 编辑目录</a-doption>
                  <a-doption>
                    <a-popconfirm content="删除前需确保目录下没有子目录或资产，确认继续吗？" @ok="deleteCatalog(catalog)">
                      <span class="danger-option"><icon-delete /> 删除目录</span>
                    </a-popconfirm>
                  </a-doption>
                </template>
              </a-dropdown>
            </div>
          </div>
          <a-empty v-if="!loading && visibleCatalogs.length === 0" :description="catalogKeyword ? '没有匹配的目录' : '还没有资产目录'">
            <template #extra><a-button v-if="!catalogKeyword" type="text" @click="openCatalog()">新建目录</a-button></template>
          </a-empty>
        </a-spin>
        <footer class="catalog-footer">
          <span>{{ catalogs.length }} 个目录</span>
          <button v-if="selectedCatalogId" @click="selectedCatalogId = ''">清除筛选</button>
        </footer>
      </aside>
      <main class="functional-content">
        <router-view />
      </main>
    </section>
    <main v-else class="functional-content functional-content--wide">
      <router-view />
    </main>

    <a-modal
      v-model:visible="catalogDialog"
      :title="editingCatalogId ? '编辑资产目录' : '新建资产目录'"
      width="520px"
      :ok-loading="saving"
      :on-before-ok="saveCatalog"
    >
      <a-form :model="catalogForm" layout="vertical">
        <a-form-item label="目录名称" required :validate-status="catalogErrors.name ? 'error' : undefined" :help="catalogErrors.name"><a-input v-model="catalogForm.name" placeholder="例如：订单核心流程" @input="delete catalogErrors.name" /></a-form-item>
        <a-form-item label="父级目录">
          <a-select v-model="catalogForm.parentId" allow-clear placeholder="不选择则创建为一级目录">
            <a-option v-for="catalog in catalogs.filter((item) => item.id !== editingCatalogId)" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="排序值"><a-input-number v-model="catalogForm.sortOrder" :min="0" :max="9999" /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.functional-shell{padding-top:0}.functional-tabs{position:sticky;top:0;z-index:10;display:flex;align-items:stretch;gap:4px;min-height:57px;margin:0 -28px 22px;padding:0 28px;background:rgba(255,255,255,.97);border-bottom:1px solid var(--line);box-shadow:0 3px 14px rgba(23,35,61,.035)}.functional-tab{position:relative;display:flex;align-items:center;gap:7px;padding:0 17px;color:var(--color-text-2);font-size:13px;font-weight:500;text-decoration:none;white-space:nowrap}.functional-tab svg{font-size:16px;color:var(--color-text-3)}.functional-tab:hover{color:var(--primary)}.functional-tab.router-link-exact-active{color:var(--primary);font-weight:650}.functional-tab.router-link-exact-active svg{color:var(--primary)}.functional-tab.router-link-exact-active::after{content:"";position:absolute;right:14px;bottom:0;left:14px;height:3px;border-radius:3px 3px 0 0;background:var(--primary)}.functional-workspace{display:grid;grid-template-columns:254px minmax(0,1fr);gap:18px;align-items:start}.asset-catalog{position:sticky;top:79px;display:flex;flex-direction:column;min-height:650px;padding:16px 12px}.catalog-title{display:flex;align-items:center;justify-content:space-between;margin:0 4px 14px}.catalog-title>div{display:grid;gap:3px}.catalog-title span{color:var(--primary);font-size:9px;font-weight:700;letter-spacing:1.2px}.catalog-title h2{margin:0;font-size:17px}.catalog-spin{display:block;margin-top:13px}.catalog-list{display:grid;gap:2px;margin-top:3px}.catalog-entry{position:relative}.catalog-row{display:grid;grid-template-columns:17px minmax(0,1fr) auto;align-items:center;gap:7px;width:100%;min-height:38px;padding:0 34px 0 12px;color:var(--color-text-2);text-align:left;border:0;border-radius:7px;background:transparent;cursor:pointer}.catalog-row svg{color:var(--color-text-3)}.catalog-row span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.catalog-row small{color:var(--color-text-3);font-size:11px}.catalog-row:hover,.catalog-row.selected{color:var(--primary);background:rgb(var(--primary-1))}.catalog-row.selected svg{color:var(--primary)}.catalog-all{margin-bottom:4px;padding-right:12px;font-weight:600}.catalog-more{position:absolute;top:5px;right:3px;opacity:0}.catalog-entry:hover .catalog-more,.catalog-entry:focus-within .catalog-more{opacity:1}.catalog-entry:hover .catalog-row small{visibility:hidden}.catalog-footer{display:flex;align-items:center;justify-content:space-between;margin-top:auto;padding:14px 4px 0;color:var(--color-text-3);font-size:11px;border-top:1px solid var(--color-border-2)}.catalog-footer button{color:var(--primary);font-size:11px;border:0;background:transparent;cursor:pointer}.functional-content{min-width:0}.functional-content--wide{width:100%}.danger-option{display:flex;align-items:center;gap:6px;color:rgb(var(--danger-6))}@media(max-width:1380px){.functional-tabs{margin-right:-22px;margin-left:-22px;padding:0 22px}.functional-tab{padding:0 12px}.functional-workspace{grid-template-columns:230px minmax(0,1fr)}}@media(max-width:1120px){.functional-tabs{overflow-x:auto}.functional-tab svg{display:none}.functional-tab{padding:0 9px}.functional-workspace{grid-template-columns:210px minmax(0,1fr)}}
</style>
