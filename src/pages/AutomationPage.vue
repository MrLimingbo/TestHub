<script setup lang="ts">
import { ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface FlowNode { id: number; type: string; title: string; subtitle: string; tone: string }
const nodes = ref<FlowNode[]>([
  { id: 1, type: 'request', title: '用户登录', subtitle: 'POST /auth/login', tone: 'blue' },
  { id: 2, type: 'extract', title: '提取访问令牌', subtitle: '$.data.accessToken → token', tone: 'violet' },
  { id: 3, type: 'condition', title: '判断用户等级', subtitle: 'level >= 3', tone: 'orange' },
  { id: 4, type: 'request', title: '创建交易订单', subtitle: 'POST /orders', tone: 'green' },
  { id: 5, type: 'assert', title: '校验交易结果', subtitle: 'status = SUCCESS', tone: 'blue' },
])
const selected = ref(nodes.value[0])
const running = ref(false)
const run = async () => { running.value = true; await new Promise((resolve) => setTimeout(resolve, 900)); running.value = false; Message.success('场景执行完成：5/5 步骤通过') }
const addNode = (type: string) => {
  const node = { id: Date.now(), type, title: type === 'wait' ? '等待步骤' : '新的请求步骤', subtitle: type === 'wait' ? '等待 1000 ms' : 'GET /resource', tone: 'blue' }
  nodes.value.push(node); selected.value = node
}
</script>

<template>
  <div class="page automation-page">
    <PageHeader eyebrow="场景自动化" title="交易核心链路回归" description="通过条件、循环、数据提取和断言编排可复用的接口测试场景。" action="运行场景" @action="run" />
    <section class="automation-toolbar"><div><a-tag color="green">已保存</a-tag><span>最近更新：2 分钟前</span><span>版本 v1.8</span></div><div><a-button @click="Message.success('场景已保存')"><icon-save /> 保存</a-button><a-button type="primary" :loading="running" @click="run"><icon-play-arrow /> 运行</a-button></div></section>
    <section class="automation-workspace">
      <aside class="step-palette"><h3>添加步骤</h3><p>拖入画布或点击添加</p><button @click="addNode('request')"><icon-thunderbolt /><span><strong>接口请求</strong><small>发送 HTTP 请求</small></span></button><button @click="addNode('condition')"><icon-branch /><span><strong>条件分支</strong><small>按表达式分流</small></span></button><button @click="addNode('wait')"><icon-clock-circle /><span><strong>等待</strong><small>延迟场景执行</small></span></button><button @click="addNode('loop')"><icon-loop /><span><strong>循环控制</strong><small>重复执行步骤</small></span></button><button @click="addNode('database')"><icon-storage /><span><strong>数据库操作</strong><small>查询与断言数据</small></span></button><button @click="addNode('script')"><icon-code /><span><strong>自定义脚本</strong><small>Runner 隔离执行</small></span></button></aside>
      <div class="flow-canvas">
        <div class="canvas-tools"><a-button-group><a-button size="small"><icon-zoom-in /></a-button><a-button size="small"><icon-zoom-out /></a-button><a-button size="small"><icon-fullscreen /></a-button></a-button-group><span>100%</span></div>
        <div class="flow-start">开始</div>
        <template v-for="node in nodes" :key="node.id"><div class="flow-line"></div><button class="flow-node" :class="[{ selected: selected.id === node.id }, `node-${node.tone}`]" @click="selected = node"><span class="node-index">{{ nodes.indexOf(node) + 1 }}</span><div><strong>{{ node.title }}</strong><small>{{ node.subtitle }}</small></div><icon-check-circle-fill class="node-check" /></button></template>
        <div class="flow-line"></div><button class="flow-add" @click="addNode('request')"><icon-plus /></button><div class="flow-line"></div><div class="flow-end">结束</div>
      </div>
      <aside class="property-panel"><div class="property-title"><div><span>步骤配置</span><a-tag size="small">{{ selected.type }}</a-tag></div><a-button shape="circle" size="mini"><icon-close /></a-button></div><a-form :model="selected" layout="vertical"><a-form-item label="步骤名称"><a-input v-model="selected.title" /></a-form-item><a-form-item label="请求地址"><a-input default-value="{{baseUrl}}/auth/login"><template #prefix><a-tag color="green" size="small">POST</a-tag></template></a-input></a-form-item><a-form-item label="失败策略"><a-select default-value="stop"><a-option value="stop">停止场景</a-option><a-option value="continue">继续执行</a-option></a-select></a-form-item><a-form-item label="超时时间"><a-input-number :default-value="30000" long><template #suffix>ms</template></a-input-number></a-form-item></a-form><a-divider /> <div class="property-section"><strong>断言</strong><a-button type="text" size="small"><icon-plus /> 添加</a-button></div><div class="assertion"><icon-check-circle-fill /><span>状态码等于 200</span></div><div class="assertion"><icon-check-circle-fill /><span>响应时间小于 1000 ms</span></div></aside>
    </section>
  </div>
</template>
