<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import MetricGrid from '@/components/MetricGrid.vue'
import PageHeader from '@/components/PageHeader.vue'
import { modules } from '@/mock/modules'

const props = defineProps<{ moduleKey: string }>()
const module = computed(() => modules[props.moduleKey] ?? modules.functional)
const keyword = ref('')
const visible = ref(false)
const formModel = reactive({ name: '', owner: '林晓', description: '' })
const filteredRows = computed(() => module.value.rows.filter((row) => String(row.name).includes(keyword.value)))
const create = () => { visible.value = true }
const save = () => { visible.value = false; Message.success(`${module.value.action.replace('新建', '')}已保存到草稿`) }
</script>

<template>
  <div class="page">
    <PageHeader :eyebrow="module.eyebrow" :title="module.title" :description="module.description" :action="module.action" @action="create" />
    <MetricGrid :metrics="module.metrics" />
    <section class="panel data-panel">
      <div class="toolbar"><div><a-input-search v-model="keyword" placeholder="搜索名称或负责人" :style="{ width: '280px' }" /><a-select default-value="all" :style="{ width: '130px' }"><a-option value="all">全部状态</a-option><a-option value="active">进行中</a-option></a-select></div><div><a-button><icon-download /> 导出</a-button><a-button><icon-filter /> 筛选</a-button></div></div>
      <a-table :columns="module.columns" :data="filteredRows" :pagination="{ pageSize: 6 }" row-key="name">
        <template #status="{ record }"><a-tag :color="record.status === '已完成' ? 'green' : record.status === '进行中' ? 'arcoblue' : record.status === '待评审' ? 'orange' : 'gray'">{{ record.status }}</a-tag></template>
        <template #name="{ record }"><div class="name-cell"><span class="file-icon"><icon-file /></span><div><strong>{{ record.name }}</strong><small>TH-{{ 2200 + filteredRows.indexOf(record) }}</small></div></div></template>
        <template #operations><a-button type="text">查看</a-button><a-button type="text">更多</a-button></template>
      </a-table>
    </section>
    <a-modal v-model:visible="visible" :title="module.action" width="560px" @ok="save">
      <a-form :model="formModel" layout="vertical"><a-form-item label="名称" required><a-input v-model="formModel.name" :placeholder="`请输入${module.title}名称`" /></a-form-item><a-form-item label="负责人"><a-select v-model="formModel.owner"><a-option>林晓</a-option><a-option>陈远</a-option></a-select></a-form-item><a-form-item label="说明"><a-textarea v-model="formModel.description" placeholder="补充目标、范围或验收条件" :auto-size="{ minRows: 4, maxRows: 6 }" /></a-form-item></a-form>
    </a-modal>
  </div>
</template>
