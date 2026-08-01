<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { sanitizeRichText } from '@/utils/richText'

const props = defineProps<{ value: string }>()
const container = ref<HTMLElement | null>(null)

function render(value: string) {
  if (!container.value) return
  const template = document.createElement('template')
  template.innerHTML = sanitizeRichText(value)
  container.value.replaceChildren(template.content.cloneNode(true))
}

watch(() => props.value, (value) => void nextTick(() => render(value)))
onMounted(() => render(props.value))
</script>

<template>
  <div ref="container" />
</template>
