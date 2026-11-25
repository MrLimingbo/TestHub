<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { aiTestingApi, type CaseCatalog, type CaseDraft, type ProjectAsset, type SavedCase } from '@/utils/aiTesting'

const props = defineProps<{
  drafts: CaseDraft[]
  api: ReturnType<typeof aiTestingApi>
  suggestedRequirementId: string | null
}>()
const emit = defineEmits<{ close: []; saved: [SavedCase[]] }>()
const rows = ref(props.drafts.map(row => ({ ...row })))
const catalogs = ref<CaseCatalog[]>([])
const requirements = ref<ProjectAsset[]>([])
const catalogId = ref('')
const requirementId = ref('')
const loading = ref(true)
const pending = ref(false)
const error = ref('')
const requestBytes = crypto.getRandomValues(new Uint8Array(16))
requestBytes[6] = (requestBytes[6] & 15) | 64
requestBytes[8] = (requestBytes[8] & 63) | 128
const requestId = [...requestBytes].map(value => value.toString(16).padStart(2, '0')).join('')
  .replace(/^(........)(....)(....)(....)(............)$/, '$1-$2-$3-$4-$5')
const selectableCatalogs = computed(() => catalogs.value.map(catalog => {
  const names = [catalog.name]
  let parent = catalog.parentId
  while (parent && names.length <= catalogs.value.length) {
    const node = catalogs.value.find(item => item.id === parent)
    if (!node) break
    names.unshift(node.name)
    parent = node.parentId
  }
  return { id: catalog.id, name: names.join(' / ') }
}))
const ready = computed(() => catalogId.value && rows.value.length &&
  rows.value.every(row => row.title.trim() && row.steps.trim() && row.expectedResult.trim()))

onMounted(async () => {
  try {
    const [folders, assets] = await Promise.all([props.api.caseCatalogs(), props.api.assets()])
    catalogs.value = folders
    requirements.value = assets.filter(asset => asset.kind === 'REQUIREMENT')
    requirementId.value = requirements.value.find(asset => asset.id === props.suggestedRequirementId)?.id ?? ''
  } catch (value) {
    error.value = value instanceof Error ? value.message : '无法加载用例目录'
  } finally {
    loading.value = false
  }
})

async function submit() {
  if (pending.value || !ready.value) return
  pending.value = true
  error.value = ''
  try {
    const saved = await props.api.save<SavedCase[]>('/case-drafts', {
      requestId, catalogId: catalogId.value, requirementId: requirementId.value || null, cases: rows.value,
    })
    emit('saved', saved)
  } catch (value) {
    error.value = value instanceof Error ? value.message : '写入用例失败'
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <a-modal
    :visible="true" title="审核用例草稿" :width="720" :footer="false" :mask-closable="false" :closable="!pending"
    @cancel="!pending && emit('close')"
  >
    <a-alert v-if="error" type="error">{{ error }}</a-alert>
    <a-spin v-if="loading" :loading="true" tip="读取测试资产" />
    <form v-else class="draft-form" @submit.prevent="submit">
      <div class="draft-target">
        <label>写入用例目录
          <select v-model="catalogId" required :disabled="pending">
            <option value="">请选择用例目录</option>
            <option v-for="item in selectableCatalogs" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>
        <label>关联需求
          <select v-model="requirementId" :disabled="pending">
            <option value="">不关联需求</option>
            <option v-for="item in requirements" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>
      </div>
      <a-alert v-if="!catalogs.length" type="warning">请先在「功能测试 → 测试用例」创建用例目录。</a-alert>
      <div class="draft-list">
        <fieldset v-for="(row, index) in rows" :key="index" :disabled="pending">
          <legend>用例 {{ index + 1 }}</legend>
          <div class="draft-target">
            <label>标题<input v-model="row.title" maxlength="200" required></label>
            <label>优先级
              <select v-model="row.priority"><option v-for="priority in ['P0', 'P1', 'P2', 'P3']" :key="priority">{{ priority }}</option></select>
            </label>
          </div>
          <label>前置条件<textarea v-model="row.precondition" rows="2" maxlength="20000" /></label>
          <label>测试步骤<textarea v-model="row.steps" rows="3" maxlength="20000" required /></label>
          <label>预期结果<textarea v-model="row.expectedResult" rows="2" maxlength="20000" required /></label>
          <a-button v-if="rows.length > 1" type="text" status="danger" @click="rows.splice(index, 1)">移除此用例</a-button>
        </fieldset>
      </div>
      <footer>
        <a-button :disabled="pending" @click="emit('close')">取消</a-button>
        <a-button html-type="submit" type="primary" :loading="pending" :disabled="!ready">确认写入 {{ rows.length }} 条草稿</a-button>
      </footer>
    </form>
  </a-modal>
</template>

<style scoped>
.draft-form,.draft-list,fieldset,label{display:grid;gap:10px;min-width:0}
.draft-target{display:grid;grid-template-columns:minmax(0,2fr) minmax(0,1fr);gap:12px}
.draft-list{max-height:48dvh;overflow:auto;padding:0 4px}
fieldset{padding:12px;border:1px solid var(--color-border-2);border-radius:8px;margin:0}
input,textarea,select{min-width:0;padding:7px 9px;border:1px solid var(--color-border-2);border-radius:5px;background:var(--color-bg-2);color:var(--color-text-1);font:inherit}
textarea{resize:vertical}
label{gap:5px;font-size:13px}
footer{display:flex;justify-content:end;gap:8px;padding-top:8px}
</style>
