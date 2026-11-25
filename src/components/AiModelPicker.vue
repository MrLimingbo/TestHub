<script setup lang="ts">
import { computed } from 'vue'
import type { ChatProvider, ModelSelection, ReasoningLevel } from '@/utils/aiTesting'

const props = defineProps<{ providers: ChatProvider[]; label: string; disabled?: boolean }>()
const model = defineModel<ModelSelection | null>({ required: true })
const choice = computed(() => props.providers.find(provider => provider.id === model.value?.providerId)
  ?.models.find(entry => entry.id === model.value?.modelId))
const key = (providerId: string, modelId: string) => JSON.stringify([providerId, modelId])
const pickerValue = computed(() => model.value ? key(model.value.providerId, model.value.modelId) : '')

function select(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (!value) { model.value = null; return }
  const [providerId, modelId] = JSON.parse(value) as [string, string]
  model.value = { providerId, modelId, reasoningLevel: null }
}

function selectReasoning(event: Event) {
  const value = (event.target as HTMLSelectElement).value as ReasoningLevel | ''
  if (model.value) model.value = { ...model.value, reasoningLevel: value || null }
}
</script>

<template>
  <div class="ai-model-picker">
    <label>
      <span>{{ label }}</span>
      <select :value="pickerValue" :disabled="disabled || !providers.length" @change="select">
        <option value="">请选择模型</option>
        <option v-if="model && !choice" :value="pickerValue" disabled>{{ model.modelId }}（已不可用）</option>
        <optgroup v-for="provider in providers" :key="provider.id" :label="provider.displayName">
          <option v-for="item in provider.models" :key="item.id" :value="key(provider.id, item.id)">
            {{ item.displayName || item.id }}
          </option>
        </optgroup>
      </select>
    </label>
    <label v-if="choice?.reasoningLevels.length">
      <span>推理等级</span>
      <select :value="model?.reasoningLevel ?? ''" :disabled="disabled" @change="selectReasoning">
        <option value="">提供商默认</option>
        <option v-for="level in choice.reasoningLevels" :key="level" :value="level">{{ level }}</option>
      </select>
    </label>
  </div>
</template>

<style scoped>
.ai-model-picker{display:flex;flex-wrap:wrap;align-items:end;gap:10px;min-width:0}
label{display:grid;gap:5px;min-width:0;color:var(--color-text-2);font-size:12px}
select{min-width:120px;max-width:310px;width:100%;height:34px;padding:0 9px;border:1px solid var(--color-border-2);border-radius:6px;background:var(--color-bg-2);color:var(--color-text-1);font:inherit}
select:focus-visible{outline:2px solid rgb(var(--primary-6));outline-offset:1px}
</style>
