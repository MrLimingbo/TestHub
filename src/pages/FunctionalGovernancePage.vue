<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Tag { id: string; name: string; color: string; createdAt: string }
interface CustomField { id: string; assetType: string; name: string; fieldKey: string; fieldType: string; required: boolean; optionsJson: string; updatedAt: string }
type AssetType = 'REQUIREMENT' | 'CASE' | 'PLAN'
type FieldType = 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT'

const context = ref<Context | null>(null)
const projectId = ref('')
const tags = ref<Tag[]>([])
const fields = ref<CustomField[]>([])
const loading = ref(true)
const saving = ref(false)
const tagForm = reactive({ name: '', color: 'arcoblue' })
const fieldDialog = ref(false)
const editingFieldId = ref<string | null>(null)
const fieldForm = reactive<{ assetType: AssetType; name: string; fieldKey: string; fieldType: FieldType; required: boolean; optionsText: string }>({ assetType: 'CASE', name: '', fieldKey: '', fieldType: 'TEXT', required: false, optionsText: '' })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const assetLabel = (assetType: string) => ({ REQUIREMENT: '需求', CASE: '测试用例', PLAN: '测试计划' }[assetType] ?? assetType)
const fieldTypeLabel = (fieldType: string) => ({ TEXT: '文本', NUMBER: '数字', DATE: '日期', SELECT: '下拉选择' }[fieldType] ?? fieldType)
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'
const fieldOptions = (field: CustomField) => { try { return JSON.parse(field.optionsJson) as string[] } catch { return [] } }

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadGovernance() {
  if (!projectId.value) return
  const [tagData, fieldData] = await Promise.all([
    read<Tag[]>(`/api/v1/projects/${projectId.value}/functional/tags`),
    read<CustomField[]>(`/api/v1/projects/${projectId.value}/functional/custom-fields`),
  ])
  tags.value = tagData; fields.value = fieldData
}
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadGovernance()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载资产治理配置失败') } finally { loading.value = false }
}
async function changeProject() { try { await loadGovernance() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
async function createTag() {
  if (!tagForm.name.trim()) { Message.warning('请填写标签名称'); return }
  saving.value = true
  try {
    await mutate<Tag>(`/api/v1/projects/${projectId.value}/functional/tags`, 'POST', tagForm)
    tagForm.name = ''; await loadGovernance(); Message.success('标签已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建标签失败') } finally { saving.value = false }
}
async function deleteTag(id: string) {
  try { await mutate(`/api/v1/projects/${projectId.value}/functional/tags/${id}`, 'DELETE'); await loadGovernance(); Message.success('标签已删除') }
  catch (error) { Message.error(error instanceof Error ? error.message : '删除标签失败') }
}
function resetFieldForm() { Object.assign(fieldForm, { assetType: 'CASE', name: '', fieldKey: '', fieldType: 'TEXT', required: false, optionsText: '' }) }
function openField(field?: CustomField) {
  editingFieldId.value = field?.id ?? null; resetFieldForm()
  if (field) Object.assign(fieldForm, { assetType: field.assetType as AssetType, name: field.name, fieldKey: field.fieldKey, fieldType: field.fieldType as FieldType, required: field.required, optionsText: fieldOptions(field).join(', ') })
  fieldDialog.value = true
}
async function saveField() {
  if (!fieldForm.name.trim() || !fieldForm.fieldKey.trim()) { Message.warning('请填写字段名称和字段标识'); return }
  const options = fieldForm.optionsText.split(/[，,]/).map((item) => item.trim()).filter(Boolean)
  saving.value = true
  try {
    const payload = { assetType: fieldForm.assetType, name: fieldForm.name, fieldKey: fieldForm.fieldKey, fieldType: fieldForm.fieldType, required: fieldForm.required, options }
    const url = editingFieldId.value ? `/api/v1/projects/${projectId.value}/functional/custom-fields/${editingFieldId.value}` : `/api/v1/projects/${projectId.value}/functional/custom-fields`
    await mutate<CustomField>(url, editingFieldId.value ? 'PUT' : 'POST', payload)
    fieldDialog.value = false; await loadGovernance(); Message.success(editingFieldId.value ? '自定义字段已更新' : '自定义字段已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存字段失败') } finally { saving.value = false }
}
async function deleteField(id: string) {
  try { await mutate(`/api/v1/projects/${projectId.value}/functional/custom-fields/${id}`, 'DELETE'); await loadGovernance(); Message.success('自定义字段已删除') }
  catch (error) { Message.error(error instanceof Error ? error.message : '删除字段失败') }
}
onMounted(load)
</script>

<template>
  <div class="page governance-page">
    <PageHeader eyebrow="ASSET GOVERNANCE" title="测试资产治理" description="统一维护项目内的分类标签与自定义字段，保障需求、用例和测试计划的资产口径一致。" action="新建自定义字段" @action="openField()" />
    <section class="governance-summary panel">
      <div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div>
      <a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select>
      <div><span>分类标签</span><strong>{{ tags.length }}</strong><small>可绑定需求、用例和计划</small></div>
      <div><span>自定义字段</span><strong>{{ fields.length }}</strong><small>按资产类型隔离配置</small></div>
      <div><span>适用资产</span><strong>3</strong><small>需求、用例、测试计划</small></div>
    </section>
    <section class="governance-grid">
      <article class="panel tag-panel">
        <div class="section-heading"><div><span class="eyebrow">PROJECT TAGS</span><h3>分类标签</h3><p>标签可通过接口绑定至项目内资产；同名标签在当前项目内唯一。</p></div><span class="count-badge">{{ tags.length }}</span></div>
        <div class="tag-create"><a-input v-model="tagForm.name" placeholder="例如：核心链路、冒烟、待评审" allow-clear @press-enter="createTag" /><a-select v-model="tagForm.color" :style="{ width: '118px' }"><a-option value="arcoblue">蓝色</a-option><a-option value="green">绿色</a-option><a-option value="orange">橙色</a-option><a-option value="red">红色</a-option><a-option value="purple">紫色</a-option></a-select><a-button type="primary" :loading="saving" @click="createTag"><icon-plus /> 添加</a-button></div>
        <a-spin :loading="loading"><div v-if="tags.length" class="tag-list"><div v-for="tag in tags" :key="tag.id" class="tag-row"><div><a-tag :color="tag.color" size="large">{{ tag.name }}</a-tag><small>创建于 {{ date(tag.createdAt) }}</small></div><a-popconfirm content="删除标签后会同时解除它与资产的绑定，确认继续吗？" @ok="deleteTag(tag.id)"><a-button type="text" status="danger" aria-label="删除标签"><icon-delete /></a-button></a-popconfirm></div></div><a-empty v-else description="尚未创建项目标签"><template #extra><span class="empty-copy">先创建用于分类、筛选与后续批量操作的标签。</span></template></a-empty></a-spin>
        <div class="governance-tip"><icon-info-circle-fill /><span>标签字典采用项目级隔离，不能绑定到其他项目的需求、用例或测试计划。</span></div>
      </article>
      <article class="panel field-panel">
        <div class="section-heading"><div><span class="eyebrow">SCHEMA EXTENSION</span><h3>自定义字段</h3><p>对不同资产类型定义补充字段，不会改变已有公共字段或 API 契约。</p></div><a-button type="primary" @click="openField()"><icon-plus /> 新建字段</a-button></div>
        <a-spin :loading="loading"><a-table :data="fields" :pagination="{ pageSize: 7 }" row-key="id" :bordered="false"><template #columns><a-table-column title="字段名称" data-index="name"><template #cell="{ record }"><div class="field-name"><span class="field-icon"><icon-settings /></span><div><strong>{{ record.name }}</strong><small>{{ record.fieldKey }}</small></div></div></template></a-table-column><a-table-column title="适用资产"><template #cell="{ record }"><a-tag color="arcoblue">{{ assetLabel(record.assetType) }}</a-tag></template></a-table-column><a-table-column title="类型"><template #cell="{ record }"><span>{{ fieldTypeLabel(record.fieldType) }}</span><small v-if="fieldOptions(record).length" class="options-copy"> · {{ fieldOptions(record).join(' / ') }}</small></template></a-table-column><a-table-column title="必填"><template #cell="{ record }"><a-tag :color="record.required ? 'orange' : 'gray'">{{ record.required ? '必填' : '选填' }}</a-tag></template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="openField(record)">编辑</a-button><a-popconfirm content="删除字段定义不会删除既有资产内容，确认继续吗？" @ok="deleteField(record.id)"><a-button type="text" status="danger">删除</a-button></a-popconfirm></a-space></template></a-table-column></template></a-table><a-empty v-if="!loading && fields.length === 0" description="还没有自定义字段"><template #extra><a-button type="outline" @click="openField()">新建第一个字段</a-button></template></a-empty></a-spin>
      </article>
    </section>
    <section class="scope-panel panel"><div class="scope-icon"><icon-lock /></div><div><strong>项目范围与权限</strong><p>只有系统管理员、组织管理员、项目管理员、测试负责人和测试工程师可以修改治理配置；读取与资产绑定始终校验当前项目范围。</p></div><router-link to="/functional" class="asset-link">返回功能测试 <icon-right /></router-link></section>
    <a-modal v-model:visible="fieldDialog" :title="editingFieldId ? '编辑自定义字段' : '新建自定义字段'" width="620px" :ok-loading="saving" @ok="saveField"><a-form :model="fieldForm" layout="vertical"><div class="form-grid"><a-form-item label="适用资产" required><a-select v-model="fieldForm.assetType" :disabled="Boolean(editingFieldId)"><a-option value="REQUIREMENT">需求</a-option><a-option value="CASE">测试用例</a-option><a-option value="PLAN">测试计划</a-option></a-select></a-form-item><a-form-item label="字段类型" required><a-select v-model="fieldForm.fieldType"><a-option value="TEXT">文本</a-option><a-option value="NUMBER">数字</a-option><a-option value="DATE">日期</a-option><a-option value="SELECT">下拉选择</a-option></a-select></a-form-item></div><div class="form-grid"><a-form-item label="字段名称" required><a-input v-model="fieldForm.name" placeholder="例如：业务域" /></a-form-item><a-form-item label="字段标识" required><a-input v-model="fieldForm.fieldKey" :disabled="Boolean(editingFieldId)" placeholder="例如：business_domain" /></a-form-item></div><a-form-item v-if="fieldForm.fieldType === 'SELECT'" label="下拉选项" required><a-input v-model="fieldForm.optionsText" placeholder="用逗号分隔，例如：交易, 会员, 营销" /><small class="form-hint">下拉选项在字段配置中维护，后续将可用于筛选与导入校验。</small></a-form-item><a-form-item><a-checkbox v-model="fieldForm.required">将此字段设为必填</a-checkbox></a-form-item><a-alert type="info">字段标识使用小写字母、数字和下划线，创建后不可修改，以保证后续导入、导出和 API 集成稳定。</a-alert></a-form></a-modal>
  </div>
</template>

<style scoped>
.governance-summary{display:grid;grid-template-columns:1.4fr 1.1fr repeat(3,.82fr);gap:18px;align-items:center;margin-bottom:20px}.governance-summary>div{display:grid;gap:4px}.governance-summary span,.governance-summary small,.section-heading p,.tag-row small,.field-name small,.options-copy,.form-hint{color:var(--color-text-3);font-size:12px}.governance-summary strong{font-size:20px}.governance-grid{display:grid;grid-template-columns:minmax(330px,.78fr) minmax(0,1.22fr);gap:18px;align-items:start}.tag-panel,.field-panel{padding:22px}.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:20px}.section-heading h3{font-size:18px;margin:4px 0 6px}.section-heading p{margin:0;line-height:1.7;max-width:490px}.count-badge{display:grid;place-items:center;min-width:34px;height:28px;border-radius:14px;background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-weight:600}.tag-create{display:grid;grid-template-columns:minmax(0,1fr) 118px auto;gap:8px;padding:12px;border-radius:9px;background:var(--color-fill-1);margin-bottom:16px}.tag-list{display:grid;gap:4px}.tag-row{display:flex;align-items:center;justify-content:space-between;padding:10px 4px;border-bottom:1px solid var(--color-border-2)}.tag-row>div{display:flex;align-items:center;gap:9px}.governance-tip{display:flex;gap:8px;margin-top:18px;padding:12px;border-radius:8px;background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-size:12px;line-height:1.6}.field-name{display:flex;gap:10px;align-items:center}.field-name strong{display:block}.field-icon{display:grid;place-items:center;width:30px;height:30px;border-radius:8px;background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.scope-panel{display:flex;align-items:center;gap:14px;margin-top:18px;padding:18px 22px}.scope-icon{display:grid;place-items:center;flex:0 0 auto;width:38px;height:38px;border-radius:10px;background:rgb(var(--success-1));color:rgb(var(--success-6))}.scope-panel>div:nth-child(2){min-width:0}.scope-panel strong{font-size:15px}.scope-panel p{margin:5px 0 0;color:var(--color-text-3);font-size:13px;line-height:1.65}.asset-link{display:flex;align-items:center;gap:4px;white-space:nowrap;color:rgb(var(--primary-6));font-size:13px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.form-hint{display:block;margin-top:6px;line-height:1.5}.empty-copy{color:var(--color-text-3);font-size:12px}@media (max-width:1100px){.governance-summary{grid-template-columns:1fr 1fr}.governance-grid{grid-template-columns:1fr}.tag-panel{min-height:auto}}@media (max-width:720px){.governance-summary,.form-grid{grid-template-columns:1fr}.tag-create{grid-template-columns:1fr}.scope-panel{align-items:flex-start;flex-wrap:wrap}.asset-link{width:100%;margin-left:52px}}
</style>
