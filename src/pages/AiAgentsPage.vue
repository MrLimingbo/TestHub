<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiCaseDraftDialog from '@/components/AiCaseDraftDialog.vue'
import AiConversationSidebar from '@/components/AiConversationSidebar.vue'
import AiModelPicker from '@/components/AiModelPicker.vue'
import { useSessionStore } from '@/stores/session'
import {
  aiTestingApi, draftInstruction, parseCaseDrafts,
  type AiOverview, type AiWorkspace, type Attachment, type CaseDraft, type Conversation,
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
const expanded = ref<string[]>([])
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
const messageInput = ref<HTMLTextAreaElement>()
const transcript = ref<HTMLElement>()
const draftReview = ref<CaseDraft[] | null>(null)
const draftRequirementId = ref<string | null>(null)
const savedCases = ref<SavedCase[]>([])
const working = ref(false)
const navigating = ref(false)
const detailFailed = ref(false)
const stopping = ref(false)
const running = ref<AbortController | null>(null)
const showArchived = ref(false)
const formError = ref('')
const saving = ref(false)
const workspaceForm = ref<{ original?: AiWorkspace; name: string } | null>(null)
const conversationForm = ref<{ original: Conversation; workspaceId: string; title: string } | null>(null)
let selection = 0

const workspaces = computed(() => overview.value?.workspaces ?? [])
const providers = computed(() => overview.value?.modelProviders ?? [])
const defaultModel = computed<ModelSelection | null>(() => {
  const provider = providers.value.find(row => row.models.length)
  return provider ? { providerId: provider.id, modelId: provider.models[0].id, reasoningLevel: null } : null
})
const workspace = computed(() => workspaces.value.find(row => row.id === workspaceId.value))
const archived = computed(() => Boolean(conversation.value?.archived || workspace.value?.archived))
const latest = computed(() => turns.value.at(-1))
const streaming = computed(() => Boolean(running.value) || latest.value?.status === 'RUNNING')
const canChat = computed(() => Boolean(overview.value?.canManage && conversation.value && !archived.value))
const sidebarBusy = computed(() => Boolean(running.value || stopping.value || working.value || uploading.value || saving.value))
const composeBusy = computed(() => streaming.value || navigating.value || sidebarBusy.value)
const chatReady = computed(() => canChat.value && validModel(selectedModel.value) && !composeBusy.value && !detailFailed.value)
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
  void router.replace({ query: { workspace: workspaceId.value || undefined,
    conversation: conversation.value?.id || undefined } })
}

function reveal(folderId: string) {
  if (!expanded.value.includes(folderId)) expanded.value.push(folderId)
}

function place(row: Conversation) {
  workspaceId.value = row.workspaceId
  reveal(row.workspaceId)
  if (archived.value) showArchived.value = true
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [data, rows] = await Promise.all([api.read<AiOverview>(''), api.read<Conversation[]>('/conversations')])
    overview.value = data
    conversations.value = rows
    const folder = workspaces.value.find(row => row.id === route.query.workspace) ?? workspaces.value.find(row => !row.archived)
    const selected = rows.find(row => row.id === route.query.conversation)
      ?? rows.find(row => row.workspaceId === folder?.id && row.agentId === route.query.agent && !row.archived)
      ?? rows.find(row => row.workspaceId === folder?.id && !row.archived)
    if (selected) await openConversation(selected)
    else if (folder) {
      workspaceId.value = folder.id
      reveal(folder.id)
      if (folder.archived) showArchived.value = true
    }
  } catch (value) {
    if (!page.signal.aborted) loadError.value = describe(value)
  } finally {
    loading.value = false
    void scrollDown()
  }
}

function selectConversation(row: Conversation) {
  resetComposer()
  savedCases.value = []
  draftRequirementId.value = null
  detailFailed.value = false
  conversation.value = row
  place(row)
  const legacyModel = overview.value?.agents.find(agent => agent.id === row.agentId)?.model
  selectedModel.value = legacyModel && validModel(legacyModel) ? legacyModel : defaultModel.value
}

async function openConversation(row: Conversation) {
  if (sidebarBusy.value || (row.id === conversation.value?.id && !error.value)) return
  const request = ++selection
  selectConversation(row)
  turns.value = []
  error.value = ''
  navigating.value = true
  updateLocation()
  try {
    const detail = await api.read<ConversationDetail>(`/conversations/${row.id}`)
    if (request !== selection || page.signal.aborted) return
    setDetail(detail)
    const previous = latest.value?.model
    if (previous && validModel(previous)) selectedModel.value = previous
    await scrollDown()
  } catch (value) {
    if (request === selection && !page.signal.aborted) {
      detailFailed.value = true
      error.value = describe(value)
    }
  } finally {
    if (request === selection) navigating.value = false
  }
}

function setDetail(detail: ConversationDetail) {
  conversation.value = detail.conversation
  turns.value = detail.turns
  if (workspaceId.value !== detail.conversation.workspaceId || archived.value) {
    place(detail.conversation)
    updateLocation()
  }
  updateConversationRow(detail.conversation)
}

function updateConversationRow(row: Conversation) {
  conversations.value = [row, ...conversations.value.filter(item => item.id !== row.id)]
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt) || right.id.localeCompare(left.id))
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

async function newConversation(folder?: AiWorkspace) {
  if (!folder || folder.archived || !overview.value?.canManage || sidebarBusy.value) return
  working.value = true
  error.value = ''
  try {
    const row = await api.save<Conversation>(`/workspaces/${folder.id}/conversations`, {})
    ++selection
    navigating.value = false
    updateConversationRow(row)
    selectConversation(row)
    turns.value = []
    updateLocation()
  } catch (value) {
    if (!page.signal.aborted) error.value = describe(value)
  } finally {
    working.value = false
  }
  await nextTick()
  messageInput.value?.focus()
}

async function send(retry = false, generateDrafts = false) {
  const model = selectedModel.value
  const row = conversation.value
  if (!chatReady.value || !model || !row) return
  const content = generateDrafts ? [message.value.trim(), draftInstruction].filter(Boolean).join('\n\n') : message.value.trim()
  if (!retry && !content) return
  if (content.length > 12000) { error.value = '消息长度不能超过 12000 个字符'; return }
  const controller = new AbortController()
  running.value = controller
  error.value = ''
  try {
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
    reveal(saved.id)
    if (saved.archived) showArchived.value = true
    if (!conversation.value) {
      workspaceId.value = saved.id
      updateLocation()
    }
  } catch (value) {
    if (!page.signal.aborted) formError.value = describe(value)
  } finally {
    saving.value = false
  }
}

function editConversation(original: Conversation) {
  formError.value = ''
  conversationForm.value = { original, workspaceId: original.workspaceId, title: original.title }
}

async function saveConversation(archive?: boolean) {
  const form = conversationForm.value
  if (!form || saving.value) return
  saving.value = true
  formError.value = ''
  try {
    const { original } = form
    const saved = await api.save<Conversation>(`/conversations/${original.id}`, {
      workspaceId: form.workspaceId, title: form.title.trim() === original.title ? null : form.title.trim(),
      archived: archive ?? original.archived, version: original.version,
    }, true)
    updateConversationRow(saved)
    reveal(saved.workspaceId)
    if (saved.archived) showArchived.value = true
    if (conversation.value?.id === saved.id) {
      conversation.value = saved
      place(saved)
      updateLocation()
    }
    conversationForm.value = null
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
  <section class="ai-agent-page" aria-label="智能体会话">
    <a-alert v-if="loadError" type="error" class="page-state">{{ loadError }}
      <a-button type="text" @click="load">重试</a-button>
    </a-alert>
    <a-spin v-else-if="loading" class="page-state" :loading="true" tip="正在加载工作区" />
    <div v-else class="agent-workbench">
      <AiConversationSidebar
        v-model:expanded="expanded" v-model:show-archived="showArchived"
        :workspaces="workspaces" :conversations="conversations" :workspace-id="workspaceId"
        :conversation-id="conversation?.id" :can-manage="Boolean(overview?.canManage)" :busy="sidebarBusy"
        @create="newConversation" @workspace="editWorkspace" @select="openConversation" @edit="editConversation"
      />

      <div class="chat-panel">
        <header v-if="conversation" class="chat-heading">
          <div>
            <span class="chat-folder"><icon-folder /><span>{{ workspace?.name }}</span></span>
            <h1 :title="conversation.title">{{ conversation.title }}</h1>
          </div>
          <a-tag v-if="archived">已归档</a-tag>
          <a-button
            v-if="overview?.canManage" type="text" aria-label="管理当前会话" title="管理会话"
            :disabled="sidebarBusy" @click="editConversation(conversation)"
          ><icon-more /></a-button>
        </header>
        <a-alert v-if="error" type="error" class="chat-error" closable @close="error = ''">
          {{ error }}
          <a-button
            v-if="conversation && !turns.length && !streaming" type="text" size="mini"
            @click="openConversation(conversation)"
          >重新读取</a-button>
        </a-alert>
        <template v-if="conversation">
          <div ref="transcript" class="transcript" aria-label="对话记录">
            <div v-if="navigating" class="chat-empty"><a-spin :loading="true" tip="正在读取会话" /></div>
            <div v-else-if="!turns.length" class="chat-empty">
              <span class="empty-icon"><icon-message /></span>
              <h2>{{ archived ? '这段会话已归档' : '开始一段对话' }}</h2>
              <p v-if="canChat">输入消息，或添加附件与测试资产。</p>
            </div>
            <article v-for="turn in turns" :key="turn.id" class="turn">
              <div class="turn-user">
                <span>你</span><p>{{ turn.userText }}</p>
                <small v-if="turn.sourceSummary">引用资料 · {{ turn.sourceSummary }}</small>
              </div>
              <div class="turn-reply">
                <span class="reply-model"><icon-robot />{{ modelLabel(turn.model) }}</span>
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
          <div v-if="canChat" class="compose-area">
            <div v-if="draftCandidate.length && !savedCases.length || !running && latest?.status === 'RUNNING'" class="chat-actions">
              <a-button
                v-if="!running && latest?.status === 'RUNNING'" type="text" size="small"
                @click="refreshConversation"
              >刷新会话</a-button>
              <a-button
                v-if="draftCandidate.length && !savedCases.length" size="small" type="outline"
                :disabled="composeBusy" @click="draftReview = draftCandidate"
              >审核并写入用例库</a-button>
            </div>
            <a-alert v-if="!defaultModel" type="warning">当前服务暂无模型，请联系系统管理员在「模型配置」维护。</a-alert>
            <form class="chat-compose" @submit.prevent="send()">
              <div v-if="attachments.length || references.length || uploading" class="references">
                <a-tag v-for="(file, index) in attachments" :key="index" :closable="!composeBusy" @close="attachments.splice(index, 1)">
                  {{ file.name }}{{ file.truncated ? '（截取前 10000 字符）' : '' }}
                </a-tag>
                <a-tag v-for="item in references" :key="item.kind + item.id" :closable="!composeBusy" @close="toggleAsset(item)">
                  {{ item.name }}
                </a-tag>
                <small v-if="uploading" role="status">正在读取附件…</small>
              </div>
              <textarea
                ref="messageInput" v-model="message" class="message-input" rows="2" maxlength="12000" aria-label="消息"
                :disabled="composeBusy && !uploading" placeholder="输入消息"
                @keydown.ctrl.enter.prevent="send()" @keydown.meta.enter.prevent="send()"
              />
              <div class="compose-tools">
                <input ref="fileInput" type="file" hidden multiple accept=".docx,.txt,.md,.csv,.json,.log" @change="upload">
                <a-dropdown trigger="click" position="tl">
                  <a-button
                    type="text" shape="circle" title="添加附件或资料" aria-label="添加附件或资料"
                    :disabled="composeBusy"
                  ><icon-plus /></a-button>
                  <template #content>
                    <a-doption :disabled="composeBusy || attachments.length >= 3" @click="fileInput?.click()">
                      <icon-attachment /> 添加附件
                    </a-doption>
                    <a-doption :disabled="composeBusy" @click="openAssets"><icon-file /> 引用测试资产</a-doption>
                    <a-doption :disabled="!chatReady" @click="send(false, true)"><icon-edit /> 生成用例草稿</a-doption>
                  </template>
                </a-dropdown>
                <div class="compose-spacer"></div>
                <a-popover trigger="click" position="tr">
                  <button
                    type="button" class="model-trigger" aria-label="选择对话模型"
                    :title="selectedModel ? modelLabel(selectedModel) : '选择模型'" :disabled="composeBusy || !defaultModel"
                  >
                    <span>{{ selectedModel ? modelLabel(selectedModel) : '选择模型' }}</span>
                    <small v-if="selectedModel?.reasoningLevel">{{ selectedModel.reasoningLevel }}</small>
                    <icon-down />
                  </button>
                  <template #content><AiModelPicker v-model="selectedModel" :providers="providers" label="对话模型" :disabled="composeBusy" /></template>
                </a-popover>
                <a-button v-if="streaming" :loading="stopping" :disabled="latest?.status !== 'RUNNING'" @click="stop">停止生成</a-button>
                <a-button
                  v-else type="primary" shape="circle" html-type="submit" title="发送消息" aria-label="发送消息"
                  :disabled="!chatReady || !message.trim()"
                ><icon-arrow-up /></a-button>
              </div>
            </form>
          </div>
        </template>
        <div v-else class="chat-empty">
          <span class="empty-icon"><icon-message /></span>
          <h1>智能体会话</h1>
          <p>{{ workspaces.length ? '从左侧打开会话，或在工作区中新建。' : '创建工作区后，即可开始新会话。' }}</p>
        </div>
      </div>
    </div>

    <a-modal
      v-if="workspaceForm" :visible="true" :title="workspaceForm.original ? '管理工作区' : '新建工作区'" :footer="false"
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
      v-if="conversationForm" :visible="true" title="管理会话" :footer="false"
      :mask-closable="false" :closable="!saving" @cancel="!saving && (conversationForm = null)"
    >
      <form class="edit-form" @submit.prevent="saveConversation()">
        <a-alert v-if="formError" type="error">{{ formError }}</a-alert>
        <label>会话名称<input v-model="conversationForm.title" maxlength="120" required></label>
        <label>所属工作区<select v-model="conversationForm.workspaceId">
          <option
            v-for="row in workspaces.filter(item => !item.archived || item.id === conversationForm?.original.workspaceId)"
            :key="row.id" :value="row.id"
          >{{ row.name }}{{ row.archived ? '（已归档）' : '' }}</option>
        </select></label>
        <footer>
          <a-button
            type="text" :disabled="saving || !conversationForm.title.trim() ||
              (conversationForm.original.archived && workspaces.some(row => row.id === conversationForm?.workspaceId && row.archived))"
            @click="saveConversation(!conversationForm.original.archived)"
          >{{ conversationForm.original.archived ? '恢复会话' : '归档会话' }}</a-button>
          <a-button :disabled="saving" @click="conversationForm = null">取消</a-button>
          <a-button
            type="primary" html-type="submit" :loading="saving"
            :disabled="!conversationForm.title.trim()"
          >保存会话</a-button>
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
.ai-agent-page { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 16px 20px 20px; }
.page-state { margin: 10px; align-self: center; }
.agent-workbench { flex: 1; min-height: 0; display: grid; grid-template-columns: clamp(205px, 22vw, 276px) minmax(0, 1fr); overflow: hidden; border: 1px solid var(--color-border-2); border-radius: 12px; background: var(--color-bg-2); }
.chat-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.chat-heading, .compose-tools, .edit-form footer { display: flex; align-items: center; gap: 10px; }
.chat-heading { padding: 12px clamp(14px, 2vw, 30px); border-bottom: 1px solid var(--color-border-2); }
.chat-heading > div { flex: 1; min-width: 0; }
.chat-heading h1 { margin: 3px 0 0; font-size: 19px; line-height: 1.35; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-folder, .reply-model { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-text-2); }
.chat-folder svg { flex-shrink: 0; }
.chat-folder > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-error { margin: 8px 14px 0; flex-shrink: 0; }
.transcript { flex: 1; min-height: 0; overflow: auto; padding: 22px clamp(16px, 4vw, 48px); }
.chat-empty { flex: 1; min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 14px; text-align: center; }
.chat-panel > .chat-empty { min-height: 0; }
.chat-empty h1, .chat-empty h2 { margin: 1px 0 0; font-size: 21px; }
.chat-empty p { margin: 0; color: var(--color-text-2); font-size: 13px; }
.empty-icon { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 13px; background: rgb(var(--primary-1)); color: rgb(var(--primary-6)); font-size: 23px; }
.turn { width: min(100%, 860px); margin: 0 auto 24px; display: grid; gap: 16px; }
.turn-user { justify-self: end; width: fit-content; max-width: 85%; min-width: 0; padding: 10px 14px; border-radius: 12px 12px 4px 12px; background: rgb(var(--primary-1)); }
.turn-reply { min-width: 0; padding: 2px 4px 10px; border-bottom: 1px solid var(--color-border-1); }
.reply-model { color: var(--color-text-2); font-weight: 600; }
.turn span, .turn small { font-size: 12px; }
.turn p { white-space: pre-wrap; overflow-wrap: anywhere; margin: 4px 0 0; line-height: 1.7; }
.turn small { display: block; margin-top: 6px; color: var(--color-text-2); }
.saved-cases { width: min(100%, 860px); margin: auto; padding: 10px 14px; border: 1px solid var(--color-border-2); border-radius: 8px; }
.compose-area { display: grid; gap: 7px; width: min(100%, 924px); align-self: center; padding: 0 clamp(16px, 2.5vw, 32px) 14px; }
.chat-actions { display: flex; justify-content: flex-end; gap: 6px; flex-wrap: wrap; }
.chat-compose { display: grid; gap: 4px; padding: 9px 11px; border: 1px solid var(--color-border-3); border-radius: 15px; background: var(--color-bg-2); }
.chat-compose:focus-within { border-color: rgb(var(--primary-5)); box-shadow: 0 0 0 2px rgb(var(--primary-1)); }
.message-input { width: 100%; min-width: 0; min-height: 48px; max-height: 24dvh; padding: 5px 3px; resize: vertical; border: 0; outline: none; color: var(--color-text-1); background: transparent; font: inherit; line-height: 1.6; }
.message-input::placeholder { color: var(--color-text-3); }
.compose-tools { min-width: 0; gap: 6px; }
.compose-spacer { flex: 1; }
.model-trigger { display: flex; align-items: center; gap: 6px; min-width: 0; max-width: min(100%, 360px); padding: 6px 9px; border: 0; border-radius: 6px; color: var(--color-text-2); background: var(--color-fill-1); font: inherit; cursor: pointer; }
.model-trigger:hover:not(:disabled) { color: rgb(var(--primary-6)); background: rgb(var(--primary-1)); }
.model-trigger:disabled { cursor: default; opacity: .65; }
.model-trigger span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-trigger svg, .model-trigger small { flex-shrink: 0; }
.references { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; max-height: 88px; overflow: auto; }
.references :deep(.arco-tag) { max-width: 100%; }
.references :deep(.arco-tag-content) { overflow: hidden; text-overflow: ellipsis; }
.edit-form, .edit-form > label { display: grid; gap: 6px; font-size: 13px; }
.edit-form { gap: 12px; }
.edit-form input:not([type=checkbox]), .edit-form select { min-width: 0; padding: 7px 9px; border: 1px solid var(--color-border-2); border-radius: 5px; background: var(--color-bg-2); color: var(--color-text-1); font: inherit; }
.edit-form footer { justify-content: end; flex-wrap: wrap; }
.asset-choices { display: grid; gap: 8px; max-height: 340px; overflow: auto; }
.asset-choices label { display: flex; align-items: flex-start; gap: 7px; overflow-wrap: anywhere; }
@media (max-width: 1250px) { .ai-agent-page { padding-inline: 12px; } .agent-workbench { grid-template-columns: 222px minmax(0, 1fr); } }
@media (max-height: 530px) { .ai-agent-page { padding-block: 6px; } .chat-heading { padding-block: 5px; } .compose-area { padding-bottom: 6px; } }
</style>
