<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'

type Protocol = 'CHAT_COMPLETIONS' | 'RESPONSES'
type RetryPolicy = 'TRANSIENT_ERRORS' | 'DISABLED'
const reasoningLevels = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'] as const
type ReasoningLevel = typeof reasoningLevels[number]
interface ReasoningOption { level: ReasoningLevel; apiValue: string }
interface Model {
  id: string
  displayName: string
  contextWindow: number | null
  maxOutputTokens: number | null
  acceptsImages: boolean
  reasoningOptions: ReasoningOption[]
}
interface Provider {
  id: string
  displayName: string
  apiBaseUrl: string
  protocol: Protocol
  retryPolicy: RetryPolicy
  apiKeyConfigured: boolean
  models: Model[]
  version: number
}
interface Draft {
  original?: Provider
  displayName: string
  apiBaseUrl: string
  protocol: Protocol
  retryPolicy: RetryPolicy
  apiKey: string
  clearApiKey: boolean
  models: (Model & { rowId: number })[]
}
interface TestResult { modelId: string; reply: string; durationMs: number }

const base = '/api/v1/model-providers'
const modelLimit = 100
const protocolLabels: Record<Protocol, string> = {
  CHAT_COMPLETIONS: 'OpenAI Chat Completions',
  RESPONSES: 'OpenAI Responses',
}
const providers = ref<Provider[]>([])
const loading = ref(true)
const loadError = ref('')
const editor = ref<Draft | null>(null)
const editorHeading = ref<HTMLElement>()
const formError = ref('')
const discoveryNotice = ref('')
const pending = ref<'save' | 'discover' | 'delete' | 'test' | null>(null)
const probe = ref<{ providerId: string; modelId: string; reasoningLevel: ReasoningLevel | '' } | null>(null)
const probeReasoningOptions = computed(() => {
  const current = probe.value
  return providers.value.find(provider => provider.id === current?.providerId)?.models
    .find(model => model.id === current?.modelId)?.reasoningOptions ?? []
})
const probeResult = ref<TestResult>()
const probeError = ref('')
const requests = new AbortController()
let nextRowId = 0

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', signal: requests.signal, ...init })
  if (response.status === 204) return undefined as T
  const body = await response.json().catch(() => ({})) as { data: T; message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown) {
  const encoded = body === undefined ? undefined : JSON.stringify(body)
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: encoded,
  })
}

function message(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    providers.value = await read<Provider[]>(base)
  } catch (error) {
    loadError.value = message(error)
  } finally {
    loading.value = false
  }
}

function rows(models: Model[]) {
  return models.map(model => ({
    ...model,
    reasoningOptions: model.reasoningOptions.map(option => ({ ...option })),
    rowId: nextRowId++,
  }))
}

function addModel(draft: Draft) {
  draft.models.push(...rows([{
    id: '', displayName: '', contextWindow: null, maxOutputTokens: null, acceptsImages: false, reasoningOptions: [],
  }]))
}

function moveModel(draft: Draft, from: number, to: number) {
  const [model] = draft.models.splice(from, 1)
  if (model) draft.models.splice(to, 0, model)
}

function addReasoningOption(model: Model) {
  const level = reasoningLevels.find(level => model.reasoningOptions.every(option => option.level !== level))
  if (level) model.reasoningOptions.push({ level, apiValue: level })
}

function hasModelInput(model: Model) {
  return !!(model.id.trim() || model.displayName.trim() || model.contextWindow || model.maxOutputTokens
    || model.acceptsImages || model.reasoningOptions.length)
}

async function edit(provider?: Provider) {
  formError.value = ''
  discoveryNotice.value = ''
  probe.value = null
  editor.value = {
    original: provider,
    displayName: provider?.displayName ?? '',
    apiBaseUrl: provider?.apiBaseUrl ?? '',
    protocol: provider?.protocol ?? 'CHAT_COMPLETIONS',
    retryPolicy: provider?.retryPolicy ?? 'TRANSIENT_ERRORS',
    apiKey: '',
    clearApiKey: false,
    models: rows(provider?.models ?? []),
  }
  await nextTick()
  editorHeading.value?.focus()
}

function restoreCatalog(draft: Draft) {
  draft.models = rows(draft.original?.models ?? [])
  discoveryNotice.value = ''
}

async function discover(draft: Draft) {
  if (pending.value) return
  formError.value = ''
  discoveryNotice.value = ''
  if (!draft.apiBaseUrl.trim()) {
    formError.value = '请先填写 API 地址'
    return
  }
  pending.value = 'discover'
  try {
    const discovered = await mutate<Model[]>(base + '/discover', 'POST', {
      providerId: draft.original?.id,
      version: draft.original?.version,
      apiBaseUrl: draft.apiBaseUrl,
      retryPolicy: draft.retryPolicy,
      apiKey: draft.clearApiKey ? undefined : draft.apiKey,
      clearApiKey: draft.clearApiKey,
    })
    const existingIds = new Set(draft.models.map(model => model.id.trim()))
    const newModels = discovered.filter(model => !existingIds.has(model.id))
    const existingRows = draft.models.filter(hasModelInput)
    const added = newModels.slice(0, modelLimit - existingRows.length)
    if (added.length) draft.models = [...existingRows, ...rows(added)]
    discoveryNotice.value = added.length
      ? `已添加 ${added.length} 个模型，保存配置后生效。`
      : discovered.length ? '没有可添加的新模型。' : '提供商未返回可用模型，可手动添加。'
    if (added.length < newModels.length) discoveryNotice.value += `目录最多保存 ${modelLimit} 个模型。`
  } catch (error) {
    formError.value = message(error)
  } finally {
    pending.value = null
  }
}

async function save(draft: Draft) {
  if (pending.value) return
  formError.value = ''
  const models = draft.models.map(({ id, displayName, contextWindow, maxOutputTokens, acceptsImages, reasoningOptions }) => ({
    id: id.trim(), displayName: displayName.trim(), contextWindow: contextWindow || null, maxOutputTokens: maxOutputTokens || null,
    acceptsImages, reasoningOptions: reasoningOptions.map(option => ({ ...option, apiValue: option.apiValue.trim() })),
  }))
  if (models.some(model => !/^[!-~]+$/.test(model.id))) {
    formError.value = '请填写模型 ID，且 ID 中不能包含空格或控制字符'
    return
  }
  if (new Set(models.map(model => model.id)).size !== models.length) {
    formError.value = '模型目录中存在重复的模型 ID'
    return
  }
  if (models.some(model => model.reasoningOptions.some(option => !/^[!-~]+$/.test(option.apiValue)))) {
    formError.value = '请填写推理等级的 API 参数值，且参数值不能包含空格或控制字符'
    return
  }
  pending.value = 'save'
  try {
    const id = draft.original?.id
    const saved = await mutate<Provider>(id ? `${base}/${id}` : base, id ? 'PUT' : 'POST', {
      displayName: draft.displayName.trim(),
      apiBaseUrl: draft.apiBaseUrl.trim(),
      protocol: draft.protocol,
      retryPolicy: draft.retryPolicy,
      apiKey: draft.clearApiKey ? undefined : draft.apiKey,
      clearApiKey: draft.clearApiKey,
      models,
      version: draft.original?.version,
    })
    providers.value = [...providers.value.filter(provider => provider.id !== saved.id), saved]
      .sort((a, b) => a.displayName.localeCompare(b.displayName, 'zh-CN'))
    editor.value = null
    Message.success('模型配置已保存')
  } catch (error) {
    formError.value = message(error)
  } finally {
    pending.value = null
  }
}

async function remove(provider: Provider) {
  if (pending.value) return
  pending.value = 'delete'
  try {
    await mutate(`${base}/${provider.id}`, 'DELETE')
    providers.value = providers.value.filter(item => item.id !== provider.id)
    if (probe.value?.providerId === provider.id) probe.value = null
    Message.success('模型提供商已删除')
  } catch (error) {
    Message.error(message(error))
  } finally {
    pending.value = null
  }
}

function toggleProbe(provider: Provider) {
  const wasOpen = probe.value?.providerId === provider.id
  probe.value = wasOpen ? null : { providerId: provider.id, modelId: provider.models[0]?.id ?? '', reasoningLevel: '' }
  clearProbeResult()
}

function clearProbeResult() {
  probeResult.value = undefined
  probeError.value = ''
}

async function testModel() {
  if (pending.value || !probe.value?.modelId) return
  const { providerId, modelId, reasoningLevel } = probe.value
  clearProbeResult()
  pending.value = 'test'
  try {
    probeResult.value = await mutate<TestResult>(`${base}/${providerId}/test`, 'POST', {
      modelId,
      reasoningLevel: reasoningLevel || undefined,
    })
  } catch (error) {
    probeError.value = message(error)
  } finally {
    pending.value = null
  }
}

onMounted(load)
onBeforeUnmount(() => requests.abort())
</script>

<template>
  <div class="page model-page">
    <header class="page-header">
      <div>
        <h1>模型配置</h1>
        <p>管理模型提供商、维护模型目录，并验证模型能否返回文本回复。</p>
      </div>
      <a-button type="primary" :disabled="loading || !!loadError || !!editor || !!pending" @click="edit()">
        <template #icon><icon-plus /></template>
        添加模型提供商
      </a-button>
    </header>

    <a-alert v-if="loadError" type="error" role="alert">
      {{ loadError }} <a-button type="text" @click="load">重新加载</a-button>
    </a-alert>
    <a-spin v-else-if="loading" :loading="true" tip="正在加载模型配置" />

    <form v-if="editor" class="panel editor" aria-labelledby="model-editor-title" @submit.prevent="save(editor)">
      <h2 id="model-editor-title" ref="editorHeading" tabindex="-1">{{ editor.original ? '编辑模型提供商' : '添加模型提供商' }}</h2>

      <fieldset :disabled="!!pending">
        <div class="field-grid">
          <label class="field">
            <span>显示名称</span>
            <input v-model="editor.displayName" required maxlength="120" placeholder="例如 DeepSeek" />
          </label>
          <label class="field">
            <span>API 地址</span>
            <input v-model="editor.apiBaseUrl" required type="url" maxlength="2000" spellcheck="false" placeholder="https://api.example.com/v1" />
          </label>
          <label class="field">
            <span>API 协议</span>
            <select v-model="editor.protocol">
              <option v-for="(label, protocol) in protocolLabels" :key="protocol" :value="protocol">{{ label }}</option>
            </select>
          </label>
          <label class="field">
            <span>重试策略</span>
            <select v-model="editor.retryPolicy">
              <option value="TRANSIENT_ERRORS">默认（仅临时错误）</option>
              <option value="DISABLED">不重试</option>
            </select>
          </label>
        </div>

        <label class="field key-field">
          <span>API 密钥</span>
          <input
            v-model="editor.apiKey" type="password" autocomplete="new-password" maxlength="4096"
            :disabled="editor.clearApiKey"
            :placeholder="editor.original?.apiKeyConfigured ? '留空以保留现有密钥' : '输入提供商的 API 密钥（如需）'"
          />
        </label>
        <label v-if="editor.original?.apiKeyConfigured" class="clear-key">
          <input v-model="editor.clearApiKey" type="checkbox" /> 清除已保存的密钥
        </label>
      </fieldset>

      <section class="catalog" aria-labelledby="model-catalog-title">
        <div class="section-heading">
          <h3 id="model-catalog-title">模型目录</h3>
          <div class="actions">
            <a-button v-if="editor.original" size="small" :disabled="!!pending" @click="restoreCatalog(editor)">
              恢复已保存目录
            </a-button>
            <a-button size="small" :loading="pending === 'discover'" :disabled="!!pending" @click="discover(editor)">
              <template #icon><icon-download /></template>
              获取可用模型
            </a-button>
          </div>
        </div>
        <p v-if="discoveryNotice" class="muted" role="status">{{ discoveryNotice }}</p>
        <p v-if="!editor.models.length" class="muted">可从提供商获取目录，也可以手动填写模型 ID。</p>
        <fieldset :disabled="!!pending" class="model-fields">
          <div v-for="(model, index) in editor.models" :key="model.rowId" class="model-entry">
            <div class="model-row">
              <label class="field">
                <span>模型 ID <span class="sr-only">{{ index + 1 }}</span></span>
                <input v-model="model.id" required maxlength="200" spellcheck="false" />
              </label>
              <label class="field">
                <span>显示名称 <span class="sr-only">（模型 {{ index + 1 }}）</span></span>
                <input v-model="model.displayName" maxlength="120" placeholder="默认使用模型 ID" />
              </label>
              <div class="actions model-actions">
                <a-button
                  type="text" size="small" :aria-label="`上移模型 ${model.id || index + 1}`"
                  :disabled="!!pending || index === 0" @click="moveModel(editor, index, index - 1)"
                >
                  <template #icon><icon-up /></template>
                </a-button>
                <a-button
                  type="text" size="small" :aria-label="`下移模型 ${model.id || index + 1}`"
                  :disabled="!!pending || index === editor.models.length - 1" @click="moveModel(editor, index, index + 1)"
                >
                  <template #icon><icon-down /></template>
                </a-button>
                <a-button
                  type="text" status="danger" size="small" :aria-label="`移除模型 ${model.id || index + 1}`"
                  :disabled="!!pending" @click="editor.models.splice(index, 1)"
                >
                  <template #icon><icon-delete /></template>
                </a-button>
              </div>
            </div>
            <details class="model-options" @invalid.capture="($event.currentTarget as HTMLDetailsElement).open = true">
              <summary>高级选项<span class="sr-only">（模型 {{ index + 1 }}）</span></summary>
              <div class="field-grid">
                <label class="field">
                  <span>上下文窗口（token）</span>
                  <input v-model="model.contextWindow" type="number" min="1" max="2147483647" placeholder="使用提供商默认值" />
                </label>
                <label class="field">
                  <span>最大输出 token 数</span>
                  <input v-model="model.maxOutputTokens" type="number" min="1" max="2147483647" placeholder="使用提供商默认值" />
                </label>
              </div>
              <label class="clear-key"><input v-model="model.acceptsImages" type="checkbox" /> 支持图片输入</label>
              <div class="reasoning-options">
                <strong>推理等级</strong>
                <div v-for="(option, position) in model.reasoningOptions" :key="position" class="model-row">
                  <div class="field">
                    <label :for="`reasoning-level-${model.rowId}-${position}`">档位 {{ position + 1 }}</label>
                    <select :id="`reasoning-level-${model.rowId}-${position}`" v-model="option.level">
                      <option
                        v-for="level in reasoningLevels" :key="level" :value="level"
                        :disabled="level !== option.level && model.reasoningOptions.some(other => other.level === level)"
                      >
                        {{ level }}
                      </option>
                    </select>
                  </div>
                  <label class="field">
                    <span>API 参数值 {{ position + 1 }}</span>
                    <input v-model="option.apiValue" required maxlength="80" spellcheck="false" />
                  </label>
                  <a-button
                    type="text" status="danger" size="small" :aria-label="`移除推理等级 ${option.level}`"
                    :disabled="!!pending" @click="model.reasoningOptions.splice(position, 1)"
                  >
                    <template #icon><icon-delete /></template>
                  </a-button>
                </div>
                <a-button
                  size="mini" :disabled="!!pending || model.reasoningOptions.length >= reasoningLevels.length"
                  @click="addReasoningOption(model)"
                >添加推理等级</a-button>
              </div>
            </details>
          </div>
        </fieldset>
        <a-button
          size="small" type="outline" :disabled="!!pending || editor.models.length >= modelLimit"
          @click="addModel(editor)"
        >
          <template #icon><icon-plus /></template>
          添加模型
        </a-button>
      </section>
      <a-alert v-if="formError" type="error" role="alert">{{ formError }}</a-alert>
      <div class="editor-actions actions">
        <a-button :disabled="!!pending" @click="editor = null">取消</a-button>
        <a-button html-type="submit" type="primary" :loading="pending === 'save'" :disabled="!!pending">保存配置</a-button>
      </div>
    </form>

    <section v-if="!loading && !loadError && !editor && !providers.length" class="panel empty">
      <div class="provider-icon"><icon-robot /></div>
      <h2>尚无模型提供商</h2>
      <p>添加一个兼容 OpenAI API 的提供商，即可配置模型并测试推理。</p>
    </section>

    <article v-for="provider in providers" :key="provider.id" class="panel provider" :aria-labelledby="`model-provider-${provider.id}`">
      <div class="provider-heading">
        <span class="provider-icon"><icon-robot /></span>
        <div class="provider-name">
          <h2 :id="`model-provider-${provider.id}`">{{ provider.displayName }}</h2>
          <p>{{ provider.apiBaseUrl }}</p>
        </div>
        <div class="actions provider-actions">
          <a-button
            type="outline" size="small" :disabled="!!editor || !!pending || !provider.models.length"
            :aria-expanded="probe?.providerId === provider.id" @click="toggleProbe(provider)"
          >
            <template #icon><icon-thunderbolt /></template>
            测试调用
          </a-button>
          <a-button size="small" :disabled="!!editor || !!pending" @click="edit(provider)">编辑</a-button>
          <a-popconfirm :content="`确定删除模型提供商“${provider.displayName}”吗？`" @ok="remove(provider)">
            <a-button type="text" status="danger" size="small" :disabled="!!editor || !!pending">删除</a-button>
          </a-popconfirm>
        </div>
      </div>
      <div class="provider-details">
        <span>{{ protocolLabels[provider.protocol] }}</span>
        <span>{{ provider.models.length }} 个模型</span>
        <a-tag :color="provider.apiKeyConfigured ? 'green' : 'gray'">{{ provider.apiKeyConfigured ? '密钥已配置' : '未配置密钥' }}</a-tag>
      </div>

      <section v-if="probe?.providerId === provider.id" class="probe" aria-label="推理测试">
        <div class="probe-controls">
          <label class="field">
            <span>测试模型</span>
            <select v-model="probe.modelId" :disabled="!!pending" @change="probe.reasoningLevel = ''; clearProbeResult()">
              <option v-for="model in provider.models" :key="model.id" :value="model.id">{{ model.displayName || model.id }}</option>
            </select>
          </label>
          <div v-if="probeReasoningOptions.length" class="field effort-field">
            <label for="probe-reasoning-level">推理等级</label>
            <select id="probe-reasoning-level" v-model="probe.reasoningLevel" :disabled="!!pending" @change="clearProbeResult">
              <option value="">默认</option>
              <option v-for="option in probeReasoningOptions" :key="option.level" :value="option.level">{{ option.level }}</option>
            </select>
          </div>
          <a-button type="primary" :loading="pending === 'test'" :disabled="!!pending" @click="testModel">发送测试请求</a-button>
        </div>
        <p class="muted">将向所选模型发送固定的测试提示，验证能否返回文本。</p>
        <a-alert v-if="probeError" type="error" role="alert">{{ probeError }}</a-alert>
        <div v-if="probeResult" class="probe-result" role="status">
          <strong><icon-check-circle-fill /> 推理成功</strong>
          <span>{{ probeResult.modelId }} · {{ probeResult.durationMs }} ms</span>
          <pre>{{ probeResult.reply }}</pre>
        </div>
      </section>
    </article>
  </div>
</template>

<style scoped>
.model-page { max-width: 1240px; margin-inline: auto; container-type: inline-size; }
h2 { margin: 0; font-size: 17px; font-weight: 650; }
h3 { margin: 0; font-size: 14px; }
.editor, .provider { margin-bottom: 14px; }
.editor { display: grid; gap: 18px; }
fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 18px; }
.field { display: grid; min-width: 0; gap: 6px; font-size: 13px; font-weight: 550; }
.field input, .field select { width: 100%; min-width: 0; min-height: 34px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--color-bg-2); color: var(--color-text-1); font-weight: 400; }
.field input:focus-visible, .field select:focus-visible { outline: 2px solid var(--primary); outline-offset: 1px; }
.field input:disabled, .field select:disabled { background: var(--color-fill-2); }
.key-field { margin-top: 14px; }
.clear-key { display: flex; align-items: center; gap: 7px; margin-top: 10px; color: var(--color-text-2); font-size: 13px; }
.clear-key input { margin: 0; }
.catalog { display: grid; gap: 10px; justify-items: start; padding: 14px; border-radius: 9px; background: var(--color-fill-1); }
.section-heading { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; }
.model-fields { width: 100%; display: grid; gap: 8px; }
.model-fields:empty { display: none; }
.model-entry { display: grid; gap: 8px; padding: 10px; border: 1px solid var(--line); border-radius: 7px; background: var(--color-bg-2); }
.model-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)) auto; align-items: end; gap: 10px; }
.model-actions { gap: 2px; }
.model-options summary { width: fit-content; color: var(--color-text-2); cursor: pointer; font-size: 12px; }
.model-options[open] summary { margin-bottom: 12px; }
.reasoning-options { display: grid; justify-items: start; gap: 8px; margin-top: 12px; font-size: 13px; }
.reasoning-options .model-row { width: 100%; }
.actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.editor-actions { justify-content: end; }
.provider-heading { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.provider-icon { flex: 0 0 36px; width: 36px; height: 36px; display: grid; place-items: center; border-radius: 9px; background: rgb(var(--primary-1)); color: var(--primary); font-size: 19px; }
.provider-name { flex: 1; min-width: 140px; overflow-wrap: anywhere; }
.provider-name p { margin: 4px 0 0; color: var(--muted); font-size: 12px; }
.provider-details { display: flex; align-items: center; gap: 8px 16px; flex-wrap: wrap; margin: 12px 0 0 48px; color: var(--color-text-2); font-size: 12px; }
.empty { display: grid; justify-items: center; gap: 10px; padding: 40px 20px; text-align: center; }
.empty p, .muted { margin: 0; color: var(--color-text-2); font-size: 13px; }
.probe { display: grid; gap: 10px; margin-top: 16px; padding: 14px; border-radius: 9px; background: var(--color-fill-1); }
.probe-controls { display: flex; align-items: end; gap: 10px; flex-wrap: wrap; }
.probe-controls .field { flex: 1; min-width: min(100%, 220px); max-width: 420px; }
.probe-controls .effort-field { min-width: 100px; max-width: 160px; }
.probe-result { display: grid; gap: 5px; padding: 10px 12px; border-left: 3px solid rgb(var(--green-6)); background: var(--color-bg-2); overflow-wrap: anywhere; }
.probe-result strong { color: rgb(var(--green-7)); }
.probe-result span { color: var(--muted); font-size: 12px; }
.probe-result pre { margin: 0; font: inherit; white-space: pre-wrap; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@container (max-width: 520px) {
  .page-header { align-items: flex-start; flex-direction: column; gap: 12px; }
  .field-grid { grid-template-columns: 1fr; }
  .model-row { grid-template-columns: minmax(0, 1fr) auto; }
  .model-row > .field { grid-column: 1; }
  .model-row > :last-child { grid-column: 2; grid-row: 2; }
  .provider-details { margin-left: 0; }
}
</style>
