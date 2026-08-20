<script setup lang="ts">
interface DataSetOption { id: string; name: string; rowCount: number }
interface ExecutionProfileOption { id: string; name: string; environmentCode: string; version: number }
interface EnvironmentOption { id: string; name: string; environmentCode: string; baseUrl: string; enabled: boolean; defaultEnvironment: boolean }

defineProps<{
  hasScenario: boolean
  scenarioEnvironment: string
  environments: EnvironmentOption[]
  failureStrategy: 'STOP' | 'CONTINUE'
  dataSets: DataSetOption[]
  profiles: ExecutionProfileOption[]
  selectedDataSetId: string
  selectedProfileId: string
  timeoutSeconds: number
  runnerConnected: boolean
  runnerTrusted: boolean
  executionEnabled: boolean
  runnerLabel: string
  notificationEnabled: boolean
  saving: boolean
  running: boolean
}>()

const emit = defineEmits<{
  'update:selectedDataSetId': [value: string]
  'update:selectedProfileId': [value: string]
  'update:timeoutSeconds': [value: number]
  'update:notificationEnabled': [value: boolean]
  'update:failureStrategy': [value: 'STOP' | 'CONTINUE']
  'update:scenarioEnvironment': [value: string]
  createProfile: []
  advanced: []
  save: []
  run: []
}>()
</script>

<template>
  <aside class="automation-run-panel">
    <div class="run-mode-tabs">
      <button class="active">功能测试</button>
      <a-tooltip content="性能测试由独立性能测试模块提供"><button disabled>性能测试</button></a-tooltip>
    </div>

    <div class="run-field">
      <label>运行环境</label>
      <a-select
        :model-value="scenarioEnvironment"
        placeholder="请选择项目环境"
        @change="emit('update:scenarioEnvironment', String($event ?? ''))"
      >
        <a-option v-for="environment in environments" :key="environment.id" :value="environment.environmentCode" :disabled="!environment.enabled">
          {{ environment.name }} · {{ environment.environmentCode }}
        </a-option>
        <template #footer><router-link class="environment-manager-link" to="/system-settings/environments"><icon-settings /> 管理项目环境</router-link></template>
      </a-select>
      <small>{{ selectedProfileId ? '运行配置会在提交时覆盖此环境' : (environments.find((item) => item.environmentCode === scenarioEnvironment)?.baseUrl || '当前环境未配置基础地址') }}</small>
    </div>

    <div class="run-field">
      <label>运行配置</label>
      <a-select
        :model-value="selectedProfileId"
        allow-clear
        placeholder="使用场景默认环境"
        @change="emit('update:selectedProfileId', String($event ?? ''))"
      >
        <a-option v-for="profile in profiles" :key="profile.id" :value="profile.id">
          {{ profile.name }} · {{ profile.environmentCode }} · v{{ profile.version }}
        </a-option>
        <template #footer><a-button type="text" long @click="emit('createProfile')"><icon-plus /> 新建运行配置</a-button></template>
      </a-select>
      <small>{{ selectedProfileId ? '已选择冻结运行配置' : '不选择时使用上方场景环境' }}</small>
    </div>

    <div class="run-field">
      <label>测试数据</label>
      <a-select
        :model-value="selectedDataSetId"
        allow-clear
        placeholder="不使用测试数据"
        @change="emit('update:selectedDataSetId', String($event ?? ''))"
      >
        <a-option v-for="dataSet in dataSets" :key="dataSet.id" :value="dataSet.id">{{ dataSet.name }} · {{ dataSet.rowCount }} 行</a-option>
      </a-select>
      <small>{{ selectedDataSetId ? '每行数据创建一个独立执行任务' : '按当前场景变量执行一次' }}</small>
    </div>

    <div class="run-number-grid">
      <div class="run-field"><label>循环次数 <icon-info-circle /></label><a-input-number :model-value="1" disabled /></div>
      <div class="run-field"><label>线程数 <icon-info-circle /></label><a-input-number :model-value="1" disabled /></div>
    </div>

    <div class="run-field">
      <label>运行于</label>
      <div class="runner-choice" :class="{ online: runnerConnected, trusted: runnerTrusted }">
        <span><icon-computer /></span>
        <div><strong>隔离 API Runner</strong><small>{{ runnerLabel }}</small></div>
        <i />
      </div>
      <a-alert v-if="!executionEnabled" type="warning" :show-icon="true">场景仍可保存；Runner 未通过可信校验或未启用 HTTP 时，执行会安全收敛。</a-alert>
    </div>

    <div class="run-switch-row"><div><strong>本页提醒</strong><small>提交后提示，并在测试报告持续展示</small></div><a-switch :model-value="notificationEnabled" size="small" @change="emit('update:notificationEnabled', Boolean($event))" /></div>

    <a-collapse :bordered="false" class="advanced-settings">
      <a-collapse-item key="advanced" header="高级设置">
        <div class="run-field">
          <label>失败策略</label>
          <a-select :model-value="failureStrategy" @change="emit('update:failureStrategy', String($event) as 'STOP' | 'CONTINUE')">
            <a-option value="STOP">失败时停止</a-option>
            <a-option value="CONTINUE">失败后继续，最终标记失败</a-option>
          </a-select>
        </div>
        <div class="run-field">
          <label>执行超时</label>
          <a-input-number :model-value="timeoutSeconds" :min="10" :max="3600" @change="emit('update:timeoutSeconds', Number($event))"><template #suffix>秒</template></a-input-number>
        </div>
        <a-button type="text" size="small" @click="emit('advanced')"><icon-settings /> 覆盖变量与来源预览</a-button>
      </a-collapse-item>
    </a-collapse>

    <footer class="run-actions">
      <a-button type="primary" :disabled="!hasScenario" :loading="running" @click="emit('run')"><icon-play-arrow /> 运行</a-button>
      <a-button :disabled="!hasScenario" :loading="saving" @click="emit('save')"><icon-save /> 保存</a-button>
    </footer>
  </aside>
</template>

<style scoped>
.automation-run-panel{display:flex;flex-direction:column;min-width:0;min-height:0;padding:14px 15px 12px;background:#fff;border-left:1px solid #e7eaf1;overflow:auto}.run-mode-tabs{display:flex;gap:18px;height:43px;margin:-2px 0 15px;border-bottom:1px solid #eceef3}.run-mode-tabs button{height:43px;padding:0 4px;color:#a2a8b4;font-size:12px;border:0;border-bottom:2px solid transparent;background:transparent}.run-mode-tabs button.active{color:#6c4ce5;font-weight:700;border-bottom-color:#7458eb}.run-field{display:grid;gap:7px;margin-bottom:15px}.run-field label{display:flex;align-items:center;gap:5px;color:#5d6879;font-size:11px;font-weight:600}.run-field>small{margin-top:-2px;color:#9aa2af;font-size:9px;line-height:1.5}.environment-manager-link{display:flex;align-items:center;justify-content:center;gap:6px;padding:8px;color:#6c4ce5;text-decoration:none}.run-number-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.runner-choice{display:grid;grid-template-columns:31px minmax(0,1fr) 8px;align-items:center;gap:9px;padding:10px;border:1px solid #e3e6ee;border-radius:8px;background:#fafbfe}.runner-choice>span{display:grid;place-items:center;width:30px;height:30px;color:#7d8799;border-radius:7px;background:#edf0f5}.runner-choice>div{display:grid;gap:2px}.runner-choice strong{font-size:10px}.runner-choice small{overflow:hidden;color:#929baa;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.runner-choice i{width:7px;height:7px;border-radius:50%;background:#aeb5c0}.runner-choice.online i{background:#e4a044;box-shadow:0 0 0 3px #fff0d9}.runner-choice.trusted i{background:#28ae82;box-shadow:0 0 0 3px #e0f6ef}.run-switch-row{display:flex;align-items:center;justify-content:space-between;padding:13px 2px;border-top:1px solid #eef0f4}.run-switch-row>div{display:grid;gap:2px}.run-switch-row strong{font-size:11px}.run-switch-row small{font-size:9px;color:#9aa2af}.advanced-settings{margin:2px -5px 72px}.advanced-settings :deep(.arco-collapse-item-header){padding:10px 5px;font-size:11px}.advanced-settings :deep(.arco-collapse-item-content){padding:4px 5px 12px}.run-actions{position:sticky;bottom:-12px;display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:auto;padding:12px 0 0;background:linear-gradient(180deg,rgba(255,255,255,0),#fff 24%)}.run-actions :deep(.arco-btn-primary){background:#7758e8}
</style>
