<script setup lang="ts">
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import jsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'
import { onBeforeUnmount, onMounted, ref } from 'vue'

self.MonacoEnvironment = {
  getWorker(_: string, label: string) {
    return label === 'json' ? new jsonWorker() : new editorWorker()
  },
}

const props = withDefaults(defineProps<{ modelValue: string; language?: string; height?: number }>(), { language: 'json', height: 250 })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const host = ref<HTMLDivElement>()
let editor: monaco.editor.IStandaloneCodeEditor | undefined

onMounted(() => {
  if (!host.value) return
  editor = monaco.editor.create(host.value, {
    value: props.modelValue,
    language: props.language,
    theme: 'vs',
    minimap: { enabled: false },
    fontSize: 13,
    lineHeight: 21,
    automaticLayout: true,
    scrollBeyondLastLine: false,
    padding: { top: 12 },
  })
  editor.onDidChangeModelContent(() => emit('update:modelValue', editor?.getValue() ?? ''))
})
onBeforeUnmount(() => editor?.dispose())
</script>

<template><div ref="host" class="code-editor" :style="{ height: `${height}px` }"></div></template>
