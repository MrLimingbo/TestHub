<script setup lang="ts">
import { ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import CodeEditor from '@/components/CodeEditor.vue'
import PageHeader from '@/components/PageHeader.vue'

const method = ref('GET')
const url = ref('https://api.example.com/users/{{userId}}')
const activeTab = ref('params')
const loading = ref(false)
const response = ref(`{
  "code": 0,
  "message": "success",
  "data": {
    "id": "01JZ4B8KHY7PKR",
    "name": "林晓",
    "role": "QA Architect",
    "active": true
  }
}`)
const body = ref(`{
  "name": "{{userName}}",
  "role": "tester"
}`)
const send = async () => {
  loading.value = true
  await new Promise((resolve) => setTimeout(resolve, 700))
  loading.value = false
  Message.success('Mock 请求执行成功 · 200 OK · 326 ms')
}
</script>

<template>
  <div class="page api-debug-page">
    <PageHeader eyebrow="API 工作台" title="接口调试" description="在统一环境中完成请求构造、鉴权、脚本、断言与响应分析。" action="保存为用例" @action="Message.success('已保存为接口用例')" />
    <section class="debug-workspace">
      <aside class="request-tree">
        <div class="tree-title"><strong>接口目录</strong><a-button size="mini" shape="circle"><icon-plus /></a-button></div>
        <a-input-search size="small" placeholder="搜索接口" />
        <div class="tree-group"><span><icon-down /> 用户中心</span><button class="tree-item active"><b class="method get">GET</b><span>用户详情</span></button><button class="tree-item"><b class="method post">POST</b><span>创建用户</span></button><button class="tree-item"><b class="method put">PUT</b><span>更新资料</span></button></div>
        <div class="tree-group"><span><icon-right /> 订单服务</span></div><div class="tree-group"><span><icon-right /> 支付服务</span></div>
      </aside>
      <div class="request-editor">
        <div class="request-bar"><a-select v-model="method" :style="{ width: '108px' }"><a-option>GET</a-option><a-option>POST</a-option><a-option>PUT</a-option><a-option>DELETE</a-option></a-select><a-input v-model="url" /><a-button type="primary" :loading="loading" @click="send">发送 <icon-send /></a-button></div>
        <div class="environment-bar"><a-tag color="green">开发环境</a-tag><span>变量 12</span><span>Cookie 3</span><span>客户端证书</span><a-button type="text" size="small">环境设置</a-button></div>
        <a-tabs v-model:active-key="activeTab" class="request-tabs">
          <a-tab-pane key="params" title="Params"><a-table :pagination="false" :columns="[{ title: '', dataIndex: 'enabled', slotName: 'enabled', width: 44 }, { title: '参数名', dataIndex: 'key' }, { title: '参数值', dataIndex: 'value' }, { title: '说明', dataIndex: 'description' }]" :data="[{ enabled: true, key: 'userId', value: '10086', description: '用户唯一标识' }, { enabled: false, key: 'include', value: 'permissions', description: '扩展字段' }]"><template #enabled="{ record }"><a-checkbox :default-checked="record.enabled" /></template></a-table></a-tab-pane>
          <a-tab-pane key="body" title="Body"><CodeEditor v-model="body" :height="250" /></a-tab-pane>
          <a-tab-pane key="headers" title="Headers"><div class="empty-mini"><icon-file /> 已自动添加 6 个请求头</div></a-tab-pane>
          <a-tab-pane key="auth" title="Auth"><div class="auth-card"><span>Bearer Token</span><code v-pre>{{token}}</code><a-button size="small">编辑</a-button></div></a-tab-pane>
          <a-tab-pane key="scripts" title="前后置脚本"><CodeEditor v-model="body" language="javascript" :height="250" /></a-tab-pane>
        </a-tabs>
        <section class="response-panel">
          <div class="response-head"><div><strong>响应</strong><a-tag color="green">200 OK</a-tag><span>326 ms</span><span>1.24 KB</span></div><div><a-button size="small">保存示例</a-button><a-button size="small">生成断言</a-button></div></div>
          <a-tabs default-active-key="body" size="small"><a-tab-pane key="body" title="Body"><CodeEditor v-model="response" :height="275" /></a-tab-pane><a-tab-pane key="headers" title="Headers (8)"></a-tab-pane><a-tab-pane key="tests" title="断言 (4)"></a-tab-pane></a-tabs>
        </section>
      </div>
    </section>
  </div>
</template>
