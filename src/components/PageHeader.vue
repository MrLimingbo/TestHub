<script setup lang="ts">
defineProps<{ eyebrow: string; title: string; description: string; action?: string }>()
defineEmits<{ action: [] }>()
</script>

<template>
  <header class="page-header">
    <div>
      <div class="eyebrow">{{ eyebrow }}</div>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <a-button v-if="action" type="primary" size="large" @click="$emit('action')">
      <template #icon><icon-plus /></template>
      {{ action }}
    </a-button>
  </header>
</template>
