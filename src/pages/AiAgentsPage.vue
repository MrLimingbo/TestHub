<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiCaseDraftDialog from '@/components/AiCaseDraftDialog.vue'
import AiModelPicker from '@/components/AiModelPicker.vue'
import { useSessionStore } from '@/stores/session'
import {
  aiTestingApi, draftInstruction, parseCaseDrafts,
  type AiAgent, type AiOverview, type AiWorkspace, type Attachment, type CaseDraft, type Conversation,
  type ConversationDetail, type ModelSelection, type ProjectAsset, type SavedCase, type Turn,
} from '@/utils/aiTesting'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const page = new AbortController()
const api = aiTestingApi(session.projectId, page.signal)
const overview = ref<AiOverview>()
const loading = ref(true)
const loadError = ref('')
const error = ref('')
const workspaceId = ref('')
const agentId = ref('')
const conversations = ref<Conversation[]>([])
const conversation = ref<Conversation | null>(null)
const turns = ref<Turn[]>([])
const selectedModel = ref<ModelSelection | null>(null)
const message = ref('')
const attachments = ref<Attachment[]>([])
const references = ref<ProjectAsset[]>([])
const assetList = ref<ProjectAsset[]>([])
const assetSearch = ref('')
const assetDialog = ref(false)
const assetLoading = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const transcript = ref<HTMLElement>()
const draftReview = ref<CaseDraft[] | null>(null)
const draftRequirementId = ref<string | null>(null)
const savedCases = ref<SavedCase[]>([])
const working = ref(false)
const navigating = ref(false)
const stopping = ref(false)
const running = ref<AbortController | null>(null)
const showArchived = ref(false)
const formError = ref('')
const saving = ref(false)
const workspaceForm = ref<{ original?: AiWorkspace; name: string } | null>(null)
const agentForm = ref<{ original?: AiAgent; workspaceId: string; name: string; description: string;
  systemPrompt: string; model: ModelSelection | null } | null>(null)
let selection = 0

const workspaces = computed(() => overview.value?.workspaces ?? [])
const providers = computed(() => overview.value?.modelProviders ?? [])
const workspace = computed(() => workspaces.value.find(row => row.id === workspaceId.value))
const agents = computed(() => (overview.value?.agents ?? []).filter(row => row.workspaceId === workspaceId.value))
const agent = computed(() => agents.value.find(row => row.id === agentId.value))
const active = computed(() => Boolean(agent.value && !agent.value.archived && !workspace.value?.archived))
const latest = computed(() => turns.value.at(-1))
const streaming = computed(() => Boolean(running.value) || latest.value?.status === 'RUNNING')
const canChat = computed(() => Boolean(overview.value?.canManage && active.value))
const chatReady = computed(() => canChat.value && validModel(selectedModel.value) && !streaming.value && !working.value && !navigating.value && !uploading.value)
const filteredAssets = computed(() => {
  const term = assetSearch.value.trim().toLowerCase()
  return assetList.value.filter(row => row.name.toLowerCase().includes(term)).slice(0, 100)
})
const draftCandidate = computed(() => latest.value?.status === 'COMPLETED' && latest.value.reply
  ? parseCaseDrafts(latest.value.reply) : [])

function describe(value: unknown) {
  return value instanceof Error ? value.message : '请求失败，请稍后重试'
}

function validModel(model: ModelSelection | null) {
  const configured = providers.value.find(provider => provider.id === model?.providerId)?.models.find(entry => entry.id === model?.modelId)
  return Boolean(configured && (!model?.reasoningLevel || configured.reasoningLevels.includes(model.reasoningLevel)))
}

function modelLabel(model: ModelSelection) {
  const provider = providers.value.find(row => row.id === model.providerId)
  const name = provider?.models.find(row => row.id === model.modelId)?.displayName || model.modelId
  return provider ? provider.displayName + ' / ' + name : name
}

function updateLocation() {
  void router.replace({ query: { workspace: workspaceId.value || undefined, agent: agentId.value || undefined,
    conversation: conversation.value?.id || undefined } })
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    overview.value = await api.read<AiOverview>('')
    const first = workspaces.value.find(row => row.id === route.query.workspace) ?? workspaces.value.find(row => !row.archived)
    if (first) await openWorkspace(first.id, String(route.query.agent ?? ''), String(route.query.conversation ?? ''))
  } catch (value) {
    if (!page.signal.aborted) loadError.value = describe(value)
  } finally {
    loading.value = false
  }
}

async function openWorkspace(id: string, preferredAgent = '', preferredConversation = '') {
  workspaceId.value = id
  if (workspace.value?.archived) showArchived.value = true
  const selected = agents.value.find(row => row.id === preferredAgent) ?? agents.value.find(row => !row.archived)
  if (selected?.archived) showArchived.value = true
  await openAgent(selected?.id ?? '', preferredConversation)
}

async function openAgent(id: string, preferredConversation = '') {
  const request = ++selection
  agentId.value = id
  savedCases.value = []
  draftRequirementId.value = null
  conversations.value = []
  resetComposer()
  conversation.value = null
  turns.value = []
  selectedModel.value = agent.value?.model ?? null
  error.value = ''
  navigating.value = Boolean(id)
  if (!id) { updateLocation(); return }
  try {
    const rows = await api.read<Conversation[]>(`/agents/${id}/conversations`)
    if (request !== selection || page.signal.aborted) return
    conversations.value = rows
    const selected = rows.find(row => row.id === preferredConversation) ?? rows[0]
    if (selected) await openConversation(selected)
    else updateLocation()
  } catch (value) {
    if (request === selection && !page.signal.aborted) error.value = describe(value)
  } finally {
    if (request === selection) navigating.value = false
  }
}

async function openConversation(row: Conversation) {
  const request = ++selection
  resetComposer()
  savedCases.value = []
  draftRequirementId.value = null
  conversation.value = row
  turns.value = []
  error.value = ''
  navigating.value = true
  try {
    const detail = await api.read<ConversationDetail>(`/conversations/${row.id}`)
    if (request !== selection || page.signal.aborted) return
    setDetail(detail)
    const previous = latest.value?.model
    selectedModel.value = previous && validModel(previous) ? previous : agent.value?.model ?? null
    updateLocation()
    await scrollDown()
  } catch (value) {
    if (request === selection && !page.signal.aborted) error.value = describe(value)
  } finally {
    if (request === selection) navigating.value = false
  }
}

function setDetail(detail: ConversationDetail) {
  conversation.value = detail.conversation
  turns.value = detail.turns
  conversations.value = conversations.value.map(row => row.id === detail.conversation.id ? detail.conversation : row)
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
}

async function refreshConversation() {
  const id = conversation.value?.id
  if (!id) return
  try {
    const detail = await api.read<ConversationDetail>(`/conversations/${id}`)
    if (conversation.value?.id === id && !page.signal.aborted) setDetail(detail)
  } catch (value) {
    if (!page.signal.aborted) error.value ||= describe(value)
  }
}

function resetComposer() {
  message.value = ''
  attachments.value = []
  references.value = []
}

async function createConversation() {
  const row = await api.save<Conversation>(`/agents/${agentId.value}/conversations`, {})
  conversations.value = [row, ...conversations.value]
  conversation.value = row
  turns.value = []
  savedCases.value = []
  draftRequirementId.value = null
  updateLocation()
  return row
}

async function newConversation() {
  if (!canChat.value || streaming.value || working.value) return
  working.value = true
  error.value = ''
  try {
    await createConversation()
    resetComposer()
    selectedModel.value = agent.value?.model ?? null
  } catch (value) {
    if (!page.signal.aborted) error.value = describe(value)
  } finally {
    working.value = false
  }
}

async function send(retry = false, generateDrafts = false) {
  const model = selectedModel.value
  if (!chatReady.value || !model) return
  const content = generateDrafts ? [message.value.trim(), draftInstruction].filter(Boolean).join('\n\n') : message.value.trim()
  if (!retry && !content) return
  if (content.length > 12000) { error.value = '消息长度不能超过 12000 个字符'; return }
  const controller = new AbortController()
  running.value = controller
  error.value = ''
  try {
    const row = conversation.value ?? await createConversation()
    const last = latest.value
    await api.send(row.id, {
      text: retry ? null : content, previousTurnId: last?.id ?? null, retryTurnId: retry ? last?.id ?? null : null,
      model, attachments: retry ? [] : attachments.value,
      assets: retry ? [] : references.value.map(({ kind, id }) => ({ kind, id })),
    }, controller.signal, event => {
      if (page.signal.aborted) return
      if (event.type === 'turn') {
        turns.value = [...turns.value.filter(turn => turn.id !== event.data.id), event.data]
        if (!retry) {
          draftRequirementId.value = references.value.find(item => item.kind === 'REQUIREMENT')?.id ?? null
          resetComposer()
        }
        savedCases.value = []
      } else if (event.type === 'delta' && latest.value) {
        latest.value.reply = (latest.value.reply ?? '') + event.data.text
      } else if (event.type === 'failure') error.value = event.data.message
      void scrollDown()
    })
  } catch (value) {
    if (!controller.signal.aborted && !page.signal.aborted) error.value = describe(value)
  } finally {
    if (!page.signal.aborted) await refreshConversation()
    running.value = null
  }
}

async function stop() {
  const row = conversation.value
  const turn = latest.value
  if (!row || !turn || turn.status !== 'RUNNING' || stopping.value) return
  stopping.value = true
  error.value = ''
  try {
    await api.stop(row.id, turn)
    running.value?.abort()
    if (!running.value) await refreshConversation()
  } catch (value) {
    if (!page.signal.aborted) error.value = describe(value)
  } finally {
    stopping.value = false
  }
}

async function scrollDown() {
  await nextTick()
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight
}

function editWorkspace(original?: AiWorkspace) {
  formError.value = ''
  workspaceForm.value = { original, name: original?.name ?? '' }
}

async function saveWorkspace(archived?: boolean) {
  const form = workspaceForm.value
  if (!form || saving.value || !overview.value) return
  saving.value = true
  formError.value = ''
  try {
    const saved = await api.save<AiWorkspace>(form.original ? `/workspaces/${form.original.id}` : '/workspaces', {
      name: form.name, archived: archived ?? form.original?.archived ?? false, version: form.original?.version ?? null,
    }, Boolean(form.original))
    overview.value.workspaces = replaceRow(overview.value.workspaces, saved)
    workspaceForm.value = null
    await openWorkspace(saved.id, agentId.value, conversation.value?.id)
  } catch (value) {
    if (!page.signal.aborted) formError.value = describe(value)
  } finally {
    saving.value = false
  }
}

function editAgent(original?: AiAgent) {
  formError.value = ''
  agentForm.value = {
    original, workspaceId: original?.workspaceId ?? workspaceId.value, name: original?.name ?? '',
    description: original?.description ?? '', systemPrompt: original?.systemPrompt ?? '', model: original?.model ?? null,
  }
}

async function saveAgent(archived?: boolean) {
  const form = agentForm.value
  if (!form || !form.model || saving.value || !overview.value) return
  saving.value = true
  formError.value = ''
  try {
    const { original, ...fields } = form
    const saved = await api.save<AiAgent>(original ? `/agents/${original.id}` : '/agents', {
      ...fields, archived: archived ?? original?.archived ?? false, version: original?.version ?? null,
    }, Boolean(original))
    overview.value.agents = replaceRow(overview.value.agents, saved)
    agentForm.value = null
    await openWorkspace(saved.workspaceId, saved.id, original ? conversation.value?.id : undefined)
  } catch (value) {
    if (!page.signal.aborted) formError.value = describe(value)
  } finally {
    saving.value = false
  }
}

function replaceRow<T extends { id: string; name: string; archived: boolean }>(rows: T[], saved: T) {
  return [...rows.filter(row => row.id !== saved.id), saved]
    .sort((left, right) => Number(left.archived) - Number(right.archived) || left.name.localeCompare(right.name, 'zh-CN'))
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length + attachments.value.length > 3) { error.value = '每条消息最多引用 3 个附件'; return }
  if (!files.length) return
  uploading.value = true
  error.value = ''
  try {
    attachments.value.push(...await Promise.all(files.map(api.upload)))
  } catch (value) {
    if (!page.signal.aborted) error.value = describe(value)
  } finally {
    uploading.value = false
  }
}

async function openAssets() {
  assetDialog.value = true
  assetSearch.value = ''
  assetLoading.value = true
  try {
    assetList.value = await api.assets()
  } catch (value) {
    assetDialog.value = false
    if (!page.signal.aborted) error.value = describe(value)
  } finally {
    assetLoading.value = false
  }
}

function toggleAsset(asset: ProjectAsset) {
  if (references.value.some(item => item.id === asset.id && item.kind === asset.kind)) {
    references.value = references.value.filter(item => item.id !== asset.id || item.kind !== asset.kind)
  } else if (references.value.length < 3) references.value.push(asset)
}

function onSaved(rows: SavedCase[]) {
  savedCases.value = rows
  draftReview.value = null
}

onMounted(load)
onBeforeUnmount(() => {
  running.value?.abort()
  page.abort()
})
</script>

<template>
  <section class="ai-agent-page">
    <header class="page-heading">
      <div><h1>智能体</h1><p>{{ session.currentProject?.name }} · 按工作区管理智能体和会话</p></div>
      <a-button v-if="overview?.canManage" type="primary" :disabled="streaming || working" @click="editWorkspace()">
        <template #icon><icon-plus /></template>新建 AI 工作区
      </a-button>
    </header>
    <a-alert v-if="loadError" type="error">{{ loadError }} <a-button type="text" @click="load">重试</a-button></a-alert>
    <a-spin v-else-if="loading" :loading="true" tip="正在加载智能体" />
    <div v-else class="agent-workbench">
      <aside aria-label="工作区、智能体和会话" class="agent-sidebar">
        <section>
          <header><strong>AI 工作区</strong><a-button
            v-if="overview?.canManage && workspace" size="mini" type="text"
            :disabled="streaming || working" @click="editWorkspace(workspace)"
          >管理</a-button></header>
          <select
            aria-label="AI 工作区" :value="workspaceId" :disabled="streaming || working || uploading"
            @change="openWorkspace(($event.target as HTMLSelectElement).value)"
          >
            <option v-if="!workspaceId" value="">请选择工作区</option>
            <option v-for="item in workspaces.filter(row => showArchived || !row.archived)" :key="item.id" :value="item.id">
              {{ item.name }}{{ item.archived ? '（已归档）' : '' }}
            </option>
          </select>
          <label v-if="overview?.workspaces.some(row => row.archived) || overview?.agents.some(row => row.archived)" class="archived-option">
            <input v-model="showArchived" type="checkbox" :disabled="workspace?.archived || agent?.archived">显示已归档
          </label>
        </section>
        <section v-if="workspace" class="agent-rail">
          <header><strong>智能体</strong><a-button
            v-if="overview?.canManage && !workspace.archived" size="mini" type="text"
            :disabled="streaming || working" @click="editAgent()"
          >新建智能体</a-button></header>
          <p v-if="!agents.filter(row => showArchived || !row.archived).length">工作区暂无智能体</p>
          <button
            v-for="item in agents.filter(row => showArchived || !row.archived)" :key="item.id" class="rail-item"
            :class="{ selected: item.id === agentId }" :aria-pressed="item.id === agentId"
            :disabled="streaming || working || uploading" @click="openAgent(item.id)"
          >
            <icon-robot /><span>{{ item.name }}{{ item.archived ? '（已归档）' : '' }}</span>
          </button>
        </section>
        <section v-if="agent" class="conversation-rail">
          <header><strong>我的会话</strong><a-button
            v-if="canChat" size="mini" type="text"
            :disabled="streaming || working || navigating || uploading" @click="newConversation"
          >新建会话</a-button></header>
          <p v-if="!conversations.length">暂无会话</p>
          <button
            v-for="row in conversations" :key="row.id" class="rail-item" :title="row.title"
            :class="{ selected: row.id === conversation?.id }" :aria-pressed="row.id === conversation?.id"
            :disabled="streaming || working || uploading" @click="openConversation(row)"
          >
            <icon-message /><span>{{ row.title }}</span>
          </button>
        </section>
      </aside>

      <div class="chat-panel">
        <a-alert v-if="error" type="error" class="chat-error" closable @close="error = ''">{{ error }}</a-alert>
        <template v-if="agent">
          <header class="chat-heading">
            <span class="agent-icon"><icon-robot /></span>
            <div><h2>{{ agent.name }}</h2><p v-if="agent.description">{{ agent.description }}</p></div>
            <a-button v-if="overview?.canManage" size="small" :disabled="streaming || working" @click="editAgent(agent)">编辑智能体</a-button>
          </header>
          <div ref="transcript" class="transcript" aria-label="对话记录">
            <a-spin v-if="navigating" :loading="true" tip="正在读取会话" />
            <div v-else-if="!turns.length" class="chat-empty"><icon-message /><strong>{{ active ? '开始一段对话' : '已归档的智能体' }}</strong>
              <p>{{ active ? '发送文字，也可以引用附件、需求或已有用例。' : '可以查看已有会话。' }}</p></div>
            <article v-for="turn in turns" :key="turn.id" class="turn">
              <div class="turn-user"><span>你</span><p>{{ turn.userText }}</p><small v-if="turn.sourceSummary">引用资料 · {{ turn.sourceSummary }}</small></div>
              <div class="turn-reply">
                <span>{{ modelLabel(turn.model) }}</span>
                <p v-if="turn.reply">{{ turn.reply }}</p>
                <small v-if="turn.status === 'RUNNING'">{{ turn.reply ? '正在生成…' : '正在等待模型回复…' }}</small>
                <small v-else-if="turn.status === 'STOPPED'">已停止生成</small>
                <small v-else-if="turn.status === 'FAILED'">{{ turn.failureMessage || '生成失败' }}</small>
                <a-button
                  v-if="turn.id === latest?.id && (turn.status === 'STOPPED' || turn.status === 'FAILED') && canChat"
                  :disabled="!chatReady" size="mini" type="text" @click="send(true)"
                >重试回复</a-button>
              </div>
            </article>
            <div v-if="savedCases.length" class="saved-cases" role="status">
              <strong>已写入 {{ savedCases.length }} 条用例草稿</strong>
              <ul><li v-for="row in savedCases" :key="row.id">{{ row.caseCode }} · {{ row.title }}</li></ul>
              <router-link to="/functional/cases">打开用例库</router-link>
            </div>
          </div>
          <form v-if="canChat" class="chat-compose" @submit.prevent="send()">
            <a-alert v-if="!providers.length" type="warning">当前服务暂无模型，请联系系统管理员在「模型配置」维护。</a-alert>
            <div class="compose-models">
              <AiModelPicker v-model="selectedModel" :providers="providers" label="对话模型" :disabled="streaming || working || navigating" />
              <a-button v-if="!running && latest?.status === 'RUNNING'" type="text" @click="refreshConversation">刷新会话</a-button>
            </div>
            <label class="message-field"><span>消息</span><textarea
              v-model="message" rows="3" maxlength="12000"
              :disabled="streaming || working || navigating" placeholder="输入消息，或选择资料后生成用例草稿"
              @keydown.ctrl.enter.prevent="send()" @keydown.meta.enter.prevent="send()"
            /></label>
            <div v-if="attachments.length || references.length" class="references">
              <a-tag v-for="(file, index) in attachments" :key="index" :closable="!streaming" @close="attachments.splice(index, 1)">
                {{ file.name }}{{ file.truncated ? '（截取前 10000 字符）' : '' }}</a-tag>
              <a-tag v-for="item in references" :key="item.kind + item.id" :closable="!streaming" @close="toggleAsset(item)">{{ item.name }}</a-tag>
            </div>
            <div class="compose-tools">
              <input ref="fileInput" type="file" hidden multiple accept=".docx,.txt,.md,.csv,.json,.log" @change="upload">
              <a-button size="small" :disabled="streaming || working || navigating || uploading || attachments.length >= 3" @click="fileInput?.click()">添加附件</a-button>
              <a-button size="small" :disabled="streaming || working || navigating || uploading" @click="openAssets">引用测试资产</a-button>
              <a-button size="small" :disabled="!chatReady" @click="send(false, true)">生成用例草稿</a-button>
              <a-button
                v-if="draftCandidate.length && !savedCases.length" size="small" type="outline" :disabled="working || streaming"
                @click="draftReview = draftCandidate"
              >审核并写入用例库</a-button>
              <span class="compose-spacer"></span>
              <a-button v-if="streaming" size="small" :loading="stopping" :disabled="latest?.status !== 'RUNNING'" @click="stop">停止生成</a-button>
              <a-button v-else size="small" type="primary" html-type="submit" :disabled="!chatReady || !message.trim()">发送</a-button>
            </div>
          </form>
        </template>
        <div v-else class="chat-empty"><icon-robot /><strong>{{ workspace ? '请选择或创建智能体' : '请选择或创建 AI 工作区' }}</strong></div>
      </div>
    </div>

    <a-modal
      v-if="workspaceForm" :visible="true" :title="workspaceForm.original ? '管理 AI 工作区' : '新建 AI 工作区'" :footer="false"
      :mask-closable="false" :closable="!saving" @cancel="!saving && (workspaceForm = null)"
    >
      <form class="edit-form" @submit.prevent="saveWorkspace()">
        <a-alert v-if="formError" type="error">{{ formError }}</a-alert>
        <label>工作区名称<input v-model="workspaceForm.name" maxlength="120" required></label>
        <footer>
          <a-button
            v-if="workspaceForm.original" type="text" :disabled="saving || !workspaceForm.name.trim()"
            @click="saveWorkspace(!workspaceForm.original.archived)"
          >{{ workspaceForm.original.archived ? '恢复工作区' : '归档工作区' }}</a-button>
          <a-button :disabled="saving" @click="workspaceForm = null">取消</a-button>
          <a-button type="primary" html-type="submit" :loading="saving" :disabled="!workspaceForm.name.trim()">保存工作区</a-button>
        </footer>
      </form>
    </a-modal>

    <a-modal
      v-if="agentForm" :visible="true" :title="agentForm.original ? '编辑智能体' : '新建智能体'" :footer="false"
      :mask-closable="false" :closable="!saving" @cancel="!saving && (agentForm = null)"
    >
      <form class="edit-form" @submit.prevent="saveAgent()">
        <a-alert v-if="formError" type="error">{{ formError }}</a-alert>
        <label>智能体名称<input v-model="agentForm.name" maxlength="120" required></label>
        <label>所属工作区<select v-model="agentForm.workspaceId">
          <option
            v-for="row in workspaces.filter(item => !item.archived || item.id === agentForm?.workspaceId)"
            :key="row.id" :value="row.id"
          >{{ row.name }}</option>
        </select></label>
        <label>说明<textarea v-model="agentForm.description" rows="2" maxlength="500" /></label>
        <label>系统提示词<textarea v-model="agentForm.systemPrompt" rows="4" maxlength="8000" /></label>
        <AiModelPicker v-model="agentForm.model" :providers="providers" label="默认模型" :disabled="saving" />
        <a-alert v-if="!providers.length" type="warning">请先在「模型配置」维护至少一个模型。</a-alert>
        <footer>
          <a-button
            v-if="agentForm.original" type="text"
            :disabled="saving || !agentForm.name.trim() || (agentForm.original.archived && !validModel(agentForm.model))"
            @click="saveAgent(!agentForm.original.archived)"
          >{{ agentForm.original.archived ? '恢复智能体' : '归档智能体' }}</a-button>
          <a-button :disabled="saving" @click="agentForm = null">取消</a-button>
          <a-button
            type="primary" html-type="submit" :loading="saving"
            :disabled="!agentForm.name.trim() || (!agentForm.original?.archived && !validModel(agentForm.model))"
          >保存智能体</a-button>
        </footer>
      </form>
    </a-modal>

    <a-modal :visible="assetDialog" title="引用测试资产" :footer="false" @cancel="assetDialog = false">
      <div class="edit-form">
        <label>搜索需求或用例<input v-model="assetSearch" placeholder="输入编号或名称"></label>
        <a-spin v-if="assetLoading" :loading="true" />
        <div v-else class="asset-choices">
          <p v-if="!filteredAssets.length">当前项目没有匹配的需求或用例</p>
          <label v-for="item in filteredAssets" :key="item.kind + item.id">
            <input
              type="checkbox" :checked="references.some(row => row.id === item.id && row.kind === item.kind)"
              :disabled="references.length >= 3 && !references.some(row => row.id === item.id && row.kind === item.kind)"
              @change="toggleAsset(item)"
            >
            <span>{{ item.kind === 'REQUIREMENT' ? '需求' : '用例' }} · {{ item.name }}</span>
          </label>
        </div>
        <footer><a-button type="primary" @click="assetDialog = false">完成（{{ references.length }}/3）</a-button></footer>
      </div>
    </a-modal>
    <AiCaseDraftDialog
      v-if="draftReview" :drafts="draftReview" :api="api"
      :suggested-requirement-id="draftRequirementId"
      @close="draftReview = null" @saved="onSaved"
    />
  </section>
</template>

<style scoped>
.ai-agent-page{height:calc(100dvh - 111px);min-height:600px;display:flex;flex-direction:column;padding:20px 24px 22px;gap:16px}
.page-heading,.agent-sidebar section>header,.chat-heading,.compose-tools,.compose-models,.edit-form footer{display:flex;align-items:center;justify-content:space-between;gap:10px}
.page-heading h1{margin:0 0 3px;font-size:25px;color:var(--color-text-1)}
.page-heading p,.chat-heading p,.agent-sidebar section>p,.chat-empty p{margin:0;color:var(--color-text-3);font-size:13px}
.agent-workbench{flex:1;min-height:0;display:grid;grid-template-columns:248px minmax(0,1fr);overflow:hidden;border:1px solid var(--color-border-2);border-radius:12px;background:var(--color-bg-2)}
.agent-sidebar{display:flex;flex-direction:column;gap:14px;overflow:auto;border-right:1px solid var(--color-border-2);padding:14px;background:var(--color-fill-1)}
.agent-sidebar section>header{margin-bottom:8px;font-size:13px;min-height:24px}
.agent-sidebar section+section{padding-top:12px;border-top:1px solid var(--color-border-2)}
.agent-sidebar select{width:100%}.archived-option{margin-top:7px;display:flex;align-items:center;gap:6px;color:var(--color-text-3);font-size:12px}
.rail-item{display:flex;align-items:center;gap:8px;width:100%;text-align:left;padding:8px;border:0;border-radius:6px;background:transparent;color:var(--color-text-2);cursor:pointer}
.rail-item:disabled{cursor:default}.rail-item svg{flex-shrink:0}.rail-item span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rail-item:hover:not(:disabled),.rail-item.selected{background:rgb(var(--primary-1));color:rgb(var(--primary-6))}
.chat-panel{display:flex;flex-direction:column;min-width:0;min-height:0}
.chat-heading{justify-content:start;padding:13px 18px;border-bottom:1px solid var(--color-border-2)}
.chat-heading>div{flex:1;min-width:0}.chat-heading h2{font-size:17px;margin:0}.chat-heading p{overflow-wrap:anywhere}
.agent-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:8px;background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-size:18px}
.chat-error{margin:9px 14px 0;flex-shrink:0}
.transcript{flex:1;min-height:0;overflow:auto;padding:16px 18px}
.chat-empty{min-height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;color:var(--color-text-2)}
.chat-empty>svg{font-size:29px;color:rgb(var(--primary-6))}
.turn{width:min(100%,840px);margin:0 auto 18px;display:grid;gap:10px}
.turn-user,.turn-reply{width:fit-content;max-width:100%;min-width:0;border-radius:10px;padding:10px 13px}
.turn-user{justify-self:end;background:rgb(var(--primary-1))}.turn-reply{background:var(--color-fill-1);border:1px solid var(--color-border-2)}
.turn span,.turn small{font-size:12px;color:var(--color-text-3)}.turn p{white-space:pre-wrap;overflow-wrap:anywhere;margin:4px 0 0;line-height:1.65}
.turn small{display:block;margin-top:6px}.saved-cases{padding:10px;border:1px solid var(--color-border-2);border-radius:8px}
.chat-compose{padding:12px 15px;border-top:1px solid var(--color-border-2);display:grid;gap:8px}
.compose-tools{justify-content:flex-start;flex-wrap:wrap}.compose-spacer{flex:1}
.message-field,.edit-form,.edit-form>label{display:grid;gap:6px;font-size:13px}
textarea,select,.edit-form input:not([type=checkbox]){min-width:0;padding:7px 9px;border:1px solid var(--color-border-2);border-radius:5px;background:var(--color-bg-2);color:var(--color-text-1);font:inherit}
textarea{resize:vertical;max-height:24dvh}.references{display:flex;flex-wrap:wrap;gap:6px}
.edit-form{gap:12px}.edit-form footer{justify-content:end}
.asset-choices{display:grid;gap:8px;max-height:340px;overflow:auto}
.asset-choices label{display:flex;align-items:flex-start;gap:7px;overflow-wrap:anywhere}
@media(max-width:1250px){.agent-workbench{grid-template-columns:210px minmax(0,1fr)}.ai-agent-page{padding-inline:16px}}
</style>
