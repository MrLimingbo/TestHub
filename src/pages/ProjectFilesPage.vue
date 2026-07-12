<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface FileObject { id: string; originalName: string; contentType: string; sizeBytes: number; uploadedBy: string; createdAt: string }

const context = ref<Context | null>(null)
const projectId = ref('')
const files = ref<FileObject[]>([])
const loading = ref(true)
const uploading = ref(false)
const input = ref<HTMLInputElement | null>(null)
const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const totalSize = computed(() => files.value.reduce((sum, file) => sum + file.sizeBytes, 0))
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const size = (value: number) => value < 1024 ? `${value} B` : value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(2)} MB`

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function loadFiles() {
  if (!projectId.value) return
  files.value = await read<FileObject[]>(`/api/v1/projects/${projectId.value}/files`)
}
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadFiles()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载文件失败') } finally { loading.value = false }
}
async function changeProject() { try { await loadFiles() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function chooseFile() { input.value?.click() }
async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || !projectId.value) return
  uploading.value = true
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    const form = new FormData(); form.append('file', file)
    const response = await fetch(`/api/v1/projects/${projectId.value}/files`, { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token }, body: form })
    const body = await response.json().catch(() => ({})) as ApiEnvelope<FileObject> & { message?: string }
    if (!response.ok) throw new Error(body.message ?? '上传失败')
    await loadFiles(); Message.success('文件已上传到 MinIO')
  } catch (error) { Message.error(error instanceof Error ? error.message : '上传失败') } finally { uploading.value = false; if (input.value) input.value.value = '' }
}
function download(file: FileObject) { window.open(`/api/v1/projects/${projectId.value}/files/${file.id}/download`, '_blank', 'noopener') }
async function remove(file: FileObject) {
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    await read<void>(`/api/v1/projects/${projectId.value}/files/${file.id}`, { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } })
    await loadFiles(); Message.success('文件已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="PROJECT STORAGE" title="项目文件" description="项目附件、执行日志和报告统一写入 MinIO。控制面仅保存文件元数据，下载时按项目权限校验。" action="上传文件" @action="chooseFile" />
    <input ref="input" class="file-input" type="file" @change="upload" />
    <nav class="settings-tabs" aria-label="项目设置导航"><router-link to="/project-settings">成员与访问令牌</router-link><router-link to="/project-settings/parameters">项目参数</router-link><router-link to="/project-settings/secrets">环境密钥</router-link><router-link to="/project-settings/files">项目文件</router-link></nav>
    <section class="panel file-summary"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>文件数量</span><strong>{{ files.length }}</strong></div><div><span>占用空间</span><strong>{{ size(totalSize) }}</strong></div></section>
    <section class="panel data-panel"><div class="file-toolbar"><div><a-tag color="arcoblue">MinIO 对象存储</a-tag><span class="toolbar-note">单文件上限 20 MB · 元数据与对象分离保存</span></div><a-button type="primary" :loading="uploading" @click="chooseFile"><icon-upload /> 上传文件</a-button></div><a-spin :loading="loading"><a-table :data="files" :pagination="false" row-key="id"><template #columns><a-table-column title="文件名称" data-index="originalName"><template #cell="{ record }"><div class="file-name"><span class="file-icon"><icon-file /></span><div><strong>{{ record.originalName }}</strong><small>{{ record.contentType }}</small></div></div></template></a-table-column><a-table-column title="大小" data-index="sizeBytes"><template #cell="{ record }">{{ size(record.sizeBytes) }}</template></a-table-column><a-table-column title="上传时间" data-index="createdAt"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="download(record)">下载</a-button><a-button type="text" status="danger" @click="remove(record)">删除</a-button></a-space></template></a-table-column></template></a-table></a-spin><a-empty v-if="!loading && files.length === 0" description="当前项目还没有文件，上传一个附件开始验证 MinIO 存储。" /></section>
  </div>
</template>

<style scoped>
.file-input{display:none}.settings-tabs{display:flex;gap:8px;margin:-8px 0 20px}.settings-tabs a{padding:8px 12px;color:var(--color-text-2);border-radius:6px;text-decoration:none}.settings-tabs a.router-link-active{color:rgb(var(--primary-6));background:rgb(var(--primary-1));font-weight:600}.file-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.file-summary div{display:grid;gap:4px}.file-summary span,.file-summary small,.file-name small,.toolbar-note{color:var(--color-text-3);font-size:12px}.file-summary strong{font-size:20px}.file-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.toolbar-note{margin-left:10px}.file-name{display:flex;align-items:center;gap:10px}.file-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;background:var(--color-fill-2);color:rgb(var(--primary-6))}.file-name small{display:block;margin-top:4px}@media (max-width:900px){.file-summary{grid-template-columns:1fr 1fr}.file-toolbar{gap:12px;align-items:flex-start;flex-direction:column}.toolbar-note{display:block;margin:6px 0 0}}
</style>
