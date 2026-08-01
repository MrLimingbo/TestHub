<script setup lang="ts">
import { Message } from '@arco-design/web-vue'
import { nextTick, onMounted, ref, watch } from 'vue'
import {
  DEFAULT_REPRODUCTION_TEMPLATE,
  richTextForEditor,
  richTextPlainText,
  sanitizeRichText,
} from '@/utils/richText'

const props = withDefaults(defineProps<{
  modelValue: string
  minHeight?: number
  placeholder?: string
}>(), {
  minHeight: 220,
  placeholder: '请输入重现步骤',
})
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const editor = ref<HTMLElement | null>(null)
const templateKey = 'testhub:defect-reproduction-template'

function syncEditor(value: string) {
  if (!editor.value) return
  const normalized = richTextForEditor(value)
  if (editor.value.innerHTML !== normalized) editor.value.innerHTML = normalized
}

function publish() {
  if (!editor.value) return
  const sanitized = sanitizeRichText(editor.value.innerHTML)
  if (sanitized !== editor.value.innerHTML) editor.value.innerHTML = sanitized
  emit('update:modelValue', sanitized)
}

function command(name: string, value?: string) {
  editor.value?.focus()
  document.execCommand(name, false, value)
  publish()
}

function addLink() {
  const href = window.prompt('请输入 HTTP 或 HTTPS 链接')
  if (href) command('createLink', href)
}

function addImage() {
  const src = window.prompt('请输入图片的 HTTP 或 HTTPS 地址')
  if (src) command('insertImage', src)
}

function addTable() {
  command('insertHTML', '<table><tbody><tr><th>字段</th><th>内容</th></tr><tr><td><br></td><td><br></td></tr></tbody></table><p><br></p>')
}

function saveTemplate() {
  if (!richTextPlainText(props.modelValue)) {
    Message.warning('请先填写模板内容')
    return
  }
  localStorage.setItem(templateKey, sanitizeRichText(props.modelValue))
  Message.success('重现步骤模板已保存')
}

function applyTemplate() {
  const template = localStorage.getItem(templateKey) || DEFAULT_REPRODUCTION_TEMPLATE
  emit('update:modelValue', sanitizeRichText(template))
  void nextTick(() => syncEditor(template))
}

watch(() => props.modelValue, syncEditor)
onMounted(() => syncEditor(props.modelValue))
</script>

<template>
  <div class="rich-editor">
    <div class="rich-editor__toolbar" role="toolbar" aria-label="重现步骤编辑工具">
      <a-select size="small" default-value="p" :style="{ width: '92px' }" aria-label="文本样式" @change="(value) => command('formatBlock', String(value))">
        <a-option value="p">正文</a-option>
        <a-option value="h1">标题 1</a-option>
        <a-option value="h2">标题 2</a-option>
        <a-option value="h3">标题 3</a-option>
      </a-select>
      <a-button size="small" aria-label="加粗" title="加粗" @click="command('bold')"><strong>B</strong></a-button>
      <a-button size="small" aria-label="斜体" title="斜体" @click="command('italic')"><em>I</em></a-button>
      <a-button size="small" aria-label="下划线" title="下划线" @click="command('underline')"><u>U</u></a-button>
      <a-button size="small" aria-label="无序列表" title="无序列表" @click="command('insertUnorderedList')">• 列表</a-button>
      <a-button size="small" aria-label="有序列表" title="有序列表" @click="command('insertOrderedList')">1. 列表</a-button>
      <a-button size="small" aria-label="插入链接" title="插入链接" @click="addLink"><icon-link /></a-button>
      <a-button size="small" aria-label="插入图片" title="插入图片" @click="addImage"><icon-image /></a-button>
      <a-button size="small" aria-label="插入表格" title="插入表格" @click="addTable"><icon-apps /></a-button>
      <a-button size="small" aria-label="撤销" title="撤销" @click="command('undo')"><icon-undo /></a-button>
      <a-button size="small" aria-label="重做" title="重做" @click="command('redo')"><icon-redo /></a-button>
      <a-button size="small" aria-label="清除格式" title="清除格式" @click="command('removeFormat')">清除格式</a-button>
      <span class="toolbar-spacer" />
      <a-button size="small" type="text" @click="saveTemplate">保存模板</a-button>
      <a-button size="small" type="text" @click="applyTemplate">应用模板</a-button>
    </div>
    <div
      ref="editor"
      class="rich-editor__content"
      contenteditable="true"
      role="textbox"
      aria-multiline="true"
      :aria-label="placeholder"
      :data-placeholder="placeholder"
      :style="{ minHeight: `${minHeight}px` }"
      @input="publish"
      @blur="publish"
    />
  </div>
</template>

<style scoped>
.rich-editor{overflow:hidden;border:1px solid var(--color-border-3);border-radius:6px;background:#fff}.rich-editor:focus-within{border-color:rgb(var(--primary-6));box-shadow:0 0 0 2px rgba(var(--primary-6),.12)}.rich-editor__toolbar{display:flex;align-items:center;gap:5px;min-height:48px;padding:7px 9px;border-bottom:1px solid var(--color-border-2);background:var(--color-fill-1)}.toolbar-spacer{flex:1}.rich-editor__content{padding:16px 18px;overflow:auto;color:var(--color-text-1);font-size:14px;line-height:1.75;outline:none}.rich-editor__content:empty::before{color:var(--color-text-4);content:attr(data-placeholder);pointer-events:none}.rich-editor__content :deep(p){margin:0 0 12px}.rich-editor__content :deep(table){width:100%;margin:10px 0;border-collapse:collapse}.rich-editor__content :deep(th),.rich-editor__content :deep(td){min-width:80px;padding:7px 9px;border:1px solid var(--color-border-3)}.rich-editor__content :deep(img){max-width:100%;height:auto}.rich-editor__content :deep(pre){padding:10px;overflow:auto;border-radius:5px;background:var(--color-fill-2)}@media(max-width:760px){.rich-editor__toolbar{flex-wrap:wrap}.toolbar-spacer{display:none}}
</style>
