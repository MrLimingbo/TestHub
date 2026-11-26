<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AiWorkspace, Conversation } from '@/utils/aiTesting'

const props = defineProps<{
  workspaces: AiWorkspace[]
  conversations: Conversation[]
  workspaceId: string
  conversationId?: string
  canManage: boolean
  busy: boolean
}>()
const expanded = defineModel<string[]>('expanded', { required: true })
const showArchived = defineModel<boolean>('showArchived', { required: true })
const emit = defineEmits<{
  create: [workspace?: AiWorkspace]
  workspace: [workspace?: AiWorkspace]
  select: [conversation: Conversation]
  edit: [conversation: Conversation]
}>()
const search = ref('')
const term = computed(() => search.value.trim().toLocaleLowerCase())
const hasArchived = computed(() => props.workspaces.some(row => row.archived) || props.conversations.some(row => row.archived))
const activeWorkspace = computed(() => props.workspaces.find(row => row.id === props.workspaceId && !row.archived)
  ?? props.workspaces.find(row => !row.archived))

const folders = computed(() => {
  const byWorkspace = new Map<string, Conversation[]>()
  for (const row of props.conversations) {
    if (row.archived && !showArchived.value) continue
    const rows = byWorkspace.get(row.workspaceId) ?? []
    rows.push(row)
    byWorkspace.set(row.workspaceId, rows)
  }
  return props.workspaces.filter(folder => showArchived.value || !folder.archived).flatMap(folder => {
    const rows = byWorkspace.get(folder.id) ?? []
    if (!term.value || folder.name.toLocaleLowerCase().includes(term.value)) return [{ folder, rows }]
    const matches = rows.filter(row => row.title.toLocaleLowerCase().includes(term.value))
    return matches.length ? [{ folder, rows: matches }] : []
  })
})

function toggle(id: string) {
  expanded.value = expanded.value.includes(id) ? expanded.value.filter(value => value !== id) : [...expanded.value, id]
}
</script>

<template>
  <aside class="conversation-sidebar" aria-label="工作区与会话">
    <div v-if="canManage" class="sidebar-primary">
      <a-button type="primary" long :disabled="busy" @click="activeWorkspace ? emit('create', activeWorkspace) : emit('workspace')">
        <template #icon><icon-plus /></template>{{ activeWorkspace ? '新建会话' : '新建工作区' }}
      </a-button>
    </div>
    <header class="sidebar-heading">
      <h2>工作区</h2>
      <a-button
        v-if="canManage && activeWorkspace" type="text" size="mini" title="新建工作区" aria-label="新建工作区"
        :disabled="busy" @click="emit('workspace')"
      ><icon-folder-add /></a-button>
    </header>
    <a-input v-model="search" size="small" allow-clear aria-label="搜索工作区和会话" placeholder="搜索工作区和会话">
      <template #prefix><icon-search /></template>
    </a-input>
    <p v-if="!folders.length" class="sidebar-empty">{{ term ? '没有找到匹配的工作区或会话' : '暂无工作区' }}</p>
    <ul v-else class="folders">
      <li v-for="{ folder, rows } in folders" :key="folder.id">
        <div class="folder-row" :class="{ 'current-folder': folder.id === workspaceId }">
          <button
            class="folder-button" type="button" :title="folder.name" :aria-expanded="Boolean(term) || expanded.includes(folder.id)"
            :aria-controls="'ai-folder-' + folder.id" :disabled="Boolean(term)" @click="toggle(folder.id)"
          >
            <icon-down v-if="term || expanded.includes(folder.id)" class="folder-chevron" />
            <icon-right v-else class="folder-chevron" />
            <icon-folder /><span>{{ folder.name }}</span><small v-if="folder.archived">已归档</small>
          </button>
          <a-button
            v-if="canManage && !folder.archived" type="text" size="mini" class="row-action"
            :aria-label="'在 ' + folder.name + ' 新建会话'" title="新建会话" :disabled="busy" @click="emit('create', folder)"
          >
            <icon-plus />
          </a-button>
          <a-button
            v-if="canManage" type="text" size="mini" class="row-action" :aria-label="'管理工作区 ' + folder.name"
            title="管理工作区" :disabled="busy" @click="emit('workspace', folder)"
          ><icon-more /></a-button>
        </div>
        <div v-show="term || expanded.includes(folder.id)" :id="'ai-folder-' + folder.id" class="folder-content">
          <p v-if="!rows.length" class="sidebar-empty">暂无会话</p>
          <ul v-else class="conversations">
            <li v-for="row in rows" :key="row.id" :class="{ 'current-conversation': row.id === conversationId }">
              <button
                class="conversation-button" type="button" :title="row.title"
                :aria-current="row.id === conversationId ? 'true' : undefined" :disabled="busy" @click="emit('select', row)"
              >
                <icon-message /><span>{{ row.title }}</span><small v-if="row.archived">已归档</small>
              </button>
              <a-button
                v-if="canManage" type="text" size="mini" class="row-action"
                :aria-label="'管理会话 ' + row.title" title="管理会话" :disabled="busy" @click="emit('edit', row)"
              >
                <icon-more />
              </a-button>
            </li>
          </ul>
        </div>
      </li>
    </ul>
    <label v-if="hasArchived" class="archive-filter"><input v-model="showArchived" type="checkbox"> 显示已归档</label>
  </aside>
</template>

<style scoped>
.conversation-sidebar { display: flex; flex-direction: column; min-height: 0; overflow: auto; padding: 18px 12px 12px; background: var(--color-fill-1); border-right: 1px solid var(--color-border-2); }
.sidebar-primary { margin-bottom: 14px; }
.sidebar-heading, .folder-row, .conversations > li { display: flex; align-items: center; gap: 2px; }
.sidebar-heading { justify-content: space-between; padding: 0 4px 8px; }
.sidebar-heading h2 { margin: 0; font-size: 14px; }
.folders, .conversations { display: grid; grid-template-columns: minmax(0, 1fr); gap: 3px; margin: 0; padding: 0; list-style: none; }
.folders { margin-top: 12px; }
.folder-row, .conversations > li { min-width: 0; border-radius: 7px; }
.folder-row:hover, .conversations > li:hover { background: var(--color-fill-2); }
.folder-button, .conversation-button { display: flex; align-items: center; gap: 7px; flex: 1; min-width: 0; min-height: 36px; padding: 4px 5px; border: 0; border-radius: 7px; background: transparent; color: var(--color-text-1); text-align: left; cursor: pointer; }
.folder-button { font-weight: 600; }
.folder-button:disabled, .conversation-button:disabled { cursor: default; }
.folder-button span, .conversation-button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.folder-button small, .conversation-button small { flex-shrink: 0; color: var(--color-text-2); font-size: 11px; font-weight: normal; }
.folder-button svg, .conversation-button svg { flex-shrink: 0; }
.folder-chevron { width: 10px; color: var(--color-text-3); }
.current-folder > .folder-button { color: rgb(var(--primary-6)); }
.folder-content { margin: 2px 0 4px 17px; padding-left: 7px; border-left: 1px solid var(--color-border-2); }
.conversations > .current-conversation { background: rgb(var(--primary-1)); box-shadow: inset 2px 0 rgb(var(--primary-6)); }
.current-conversation > .conversation-button { color: rgb(var(--primary-6)); font-weight: 600; }
.row-action { flex-shrink: 0; width: 27px; padding: 0; }
.sidebar-empty { padding: 7px 5px; margin: 0; font-size: 12px; color: var(--color-text-3); }
.archive-filter { display: flex; align-items: center; gap: 5px; margin-top: auto; padding: 12px 4px 4px; font-size: 12px; color: var(--color-text-2); }
</style>
