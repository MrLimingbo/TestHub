<script setup lang="ts">
interface CatalogOption {
  id: string
  name: string
  parentId: string | null
}

interface DefinitionEditorModel {
  catalogId: string
  method: string
  path: string
  name: string
  versionName: string
  summary: string
  description: string
  headersText: string
  queryText: string
  pathText: string
  requestBody: string
  responseStatus: number
  responseBody: string
}

withDefaults(defineProps<{
  catalogs: CatalogOption[]
  errors: Record<string, string>
  editing?: boolean
  inline?: boolean
  saving?: boolean
  showActions?: boolean
}>(), {
  editing: false,
  inline: false,
  saving: false,
  showActions: false,
})

const model = defineModel<DefinitionEditorModel>({ required: true })
const emit = defineEmits<{
  save: []
  cancel: []
  clearError: [field: string]
}>()
</script>

<template>
  <div class="api-definition-editor" :class="{ inline }">
    <header v-if="inline" class="editor-heading">
      <div>
        <span>API SPECIFICATION</span>
        <h3>编辑接口定义</h3>
        <p>接口规范与调试值分离保存；服务端校验失败时会保留当前草稿。</p>
      </div>
      <a-space v-if="showActions">
        <a-button @click="emit('cancel')">重置</a-button>
        <a-button type="primary" :loading="saving" @click="emit('save')"><icon-save /> 保存修改</a-button>
      </a-space>
    </header>

    <a-form :model="model" layout="vertical">
      <a-alert v-if="errors.form" type="error">{{ errors.form }}</a-alert>
      <div class="form-grid">
        <a-form-item label="接口目录" required :validate-status="errors.catalogId ? 'error' : undefined" :help="errors.catalogId">
          <a-select v-model="model.catalogId" @change="emit('clearError', 'catalogId')">
            <a-option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="HTTP 方法">
          <a-select v-model="model.method">
            <a-option v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']" :key="method" :value="method">{{ method }}</a-option>
          </a-select>
        </a-form-item>
      </div>
      <div class="form-grid">
        <a-form-item label="接口名称" required :validate-status="errors.name ? 'error' : undefined" :help="errors.name">
          <a-input v-model="model.name" placeholder="例如：查询订单详情" @input="emit('clearError', 'name')" />
        </a-form-item>
        <a-form-item :label="editing ? '新版本' : '初始版本'" required :validate-status="errors.versionName ? 'error' : undefined" :help="errors.versionName">
          <a-input v-model="model.versionName" placeholder="1.0.0" @input="emit('clearError', 'versionName')" />
        </a-form-item>
      </div>
      <a-form-item label="请求路径" required :validate-status="errors.path ? 'error' : undefined" :help="errors.path">
        <a-input v-model="model.path" placeholder="/api/v1/orders/{orderId}" @input="emit('clearError', 'path')">
          <template #prefix><span class="method-prefix">{{ model.method }}</span></template>
        </a-input>
      </a-form-item>
      <a-form-item label="接口摘要"><a-input v-model="model.summary" placeholder="说明该接口的业务用途" /></a-form-item>
      <a-form-item label="详细说明"><a-textarea v-model="model.description" :auto-size="{ minRows: 2, maxRows: 5 }" placeholder="补充业务规则、权限要求或注意事项" /></a-form-item>

      <a-divider orientation="left">请求参数</a-divider>
      <div class="parameter-grid">
        <a-form-item label="路径参数" extra="每行一个 JSON 数组：[名称, 类型, 是否必填, 示例值, 说明]">
          <a-textarea v-model="model.pathText" :auto-size="{ minRows: 3, maxRows: 6 }" placeholder="[&quot;orderId&quot;,&quot;string&quot;,true,&quot;10001&quot;,&quot;订单标识&quot;]" />
        </a-form-item>
        <a-form-item label="查询参数" extra="每行一个 JSON 数组：[名称, 类型, 是否必填, 示例值, 说明]">
          <a-textarea v-model="model.queryText" :auto-size="{ minRows: 3, maxRows: 6 }" placeholder="[&quot;keyword&quot;,&quot;string&quot;,false,&quot;TestHub:API&quot;,&quot;搜索关键词&quot;]" />
        </a-form-item>
      </div>
      <a-form-item label="请求头" extra="每行一个 JSON 数组：[名称, 类型, 是否必填, 示例值, 说明]">
        <a-textarea v-model="model.headersText" :auto-size="{ minRows: 3, maxRows: 6 }" placeholder="[&quot;Authorization&quot;,&quot;string&quot;,true,&quot;&quot;,&quot;Bearer Token&quot;]" />
      </a-form-item>
      <a-form-item label="请求体 JSON" :validate-status="errors.requestBody ? 'error' : undefined" :help="errors.requestBody">
        <a-textarea v-model="model.requestBody" :auto-size="{ minRows: 6, maxRows: 12 }" @input="emit('clearError', 'requestBody')" />
      </a-form-item>

      <a-divider orientation="left">返回响应</a-divider>
      <div class="response-grid">
        <a-form-item label="响应状态码" required :validate-status="errors.responseStatus ? 'error' : undefined" :help="errors.responseStatus">
          <a-input-number v-model="model.responseStatus" :min="100" :max="599" @change="emit('clearError', 'responseStatus')" />
        </a-form-item>
        <a-form-item label="响应 Content-Type">
          <a-input model-value="application/json" readonly />
        </a-form-item>
      </div>
      <a-form-item label="响应示例 JSON" :validate-status="errors.responseBody ? 'error' : undefined" :help="errors.responseBody">
        <a-textarea v-model="model.responseBody" :auto-size="{ minRows: 7, maxRows: 14 }" @input="emit('clearError', 'responseBody')" />
      </a-form-item>
    </a-form>
  </div>
</template>

<style scoped>
.api-definition-editor.inline{max-width:1080px;padding:24px 30px 54px}.editor-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:22px;padding-bottom:18px;border-bottom:1px solid var(--color-border-2)}.editor-heading>div{display:grid;gap:5px}.editor-heading span{color:#8b63e9;font-size:11px;font-weight:700;letter-spacing:1.2px}.editor-heading h3{margin:0;font-size:22px}.editor-heading p{margin:0;color:var(--color-text-3);font-size:12px}.form-grid,.parameter-grid,.response-grid{display:grid;grid-template-columns:1.3fr .7fr;gap:16px}.parameter-grid{grid-template-columns:1fr 1fr}.response-grid{grid-template-columns:220px minmax(0,1fr)}.method-prefix{color:#ea6a24;font-size:11px;font-weight:750}.response-grid :deep(.arco-input-number){width:100%}@media(max-width:760px){.editor-heading{flex-direction:column}.form-grid,.parameter-grid,.response-grid{grid-template-columns:1fr}.api-definition-editor.inline{padding:20px}}
</style>
