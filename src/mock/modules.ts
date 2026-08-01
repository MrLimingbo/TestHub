import type { ModuleDefinition } from '@/types/platform'

const commonMetrics = [
  { label: '今日执行', value: '128', delta: '+18.2%', tone: 'blue' as const },
  { label: '通过率', value: '96.8%', delta: '+2.4%', tone: 'green' as const },
  { label: '待处理', value: '17', delta: '-6', tone: 'orange' as const },
  { label: '本周新增', value: '42', delta: '+12', tone: 'violet' as const },
]

const defaults = {
  metrics: commonMetrics,
  columns: [
    { title: '名称', dataIndex: 'name' },
    { title: '所属项目', dataIndex: 'project' },
    { title: '状态', dataIndex: 'status', width: 120 },
    { title: '负责人', dataIndex: 'owner', width: 120 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180 },
  ],
  rows: [
    { name: '核心交易回归', project: '交易中台 V3', status: '进行中', owner: '林晓', updatedAt: '10 分钟前' },
    { name: '会员中心冒烟验证', project: '会员增长平台', status: '已完成', owner: '陈远', updatedAt: '今天 09:42' },
    { name: '支付链路兼容性', project: '统一支付平台', status: '待评审', owner: '周敏', updatedAt: '昨天 18:30' },
    { name: '营销活动稳定性', project: '营销云', status: '草稿', owner: '李然', updatedAt: '07-09 15:20' },
  ],
}

export const modules: Record<string, ModuleDefinition> = {
  functional: { key: 'functional', title: '功能测试', eyebrow: '质量资产', description: '从需求到缺陷的完整测试闭环，确保每次交付可追溯、可度量。', action: '新建测试用例', ...defaults },
  'api-design': { key: 'api-design', title: '接口设计与文档', eyebrow: 'API 资产', description: '统一维护接口定义、数据模型、示例、版本与在线文档。', action: '新建接口', ...defaults },
  mock: { key: 'mock', title: 'Mock 服务', eyebrow: 'API 资产', description: '以接口示例和规则快速生成稳定、可协作的模拟服务。', action: '新建 Mock', ...defaults },
  'project-settings': { key: 'project-settings', title: '项目设置', eyebrow: '项目治理', description: '维护成员、环境、参数、通知与项目级安全策略。', action: '添加成员', ...defaults },
  'system-settings': { key: 'system-settings', title: '系统设置', eyebrow: '平台治理', description: '管理用户、认证、安全策略与基础设施配置。', action: '新建配置', ...defaults },
  audit: { key: 'audit', title: '权限与审计', eyebrow: '安全中心', description: '跟踪高风险操作、权限变化和关键资源访问记录。', action: '导出审计日志', ...defaults },
  integrations: { key: 'integrations', title: '通知与集成', eyebrow: '连接中心', description: '通过 Webhook、邮件和 CI/CD 将测试能力融入交付流程。', action: '新建连接', ...defaults },
  tasks: { key: 'tasks', title: '任务中心', eyebrow: '运行态', description: '统一查看执行队列、定时任务、运行日志与失败重试。', action: '创建定时任务', ...defaults },
  'ui-automation': { key: 'ui-automation', title: 'Web UI 自动化', eyebrow: 'Playwright', description: '通过页面对象和可视化步骤编排稳定的浏览器自动化用例。', action: '新建浏览器用例', ...defaults },
  'mobile-automation': { key: 'mobile-automation', title: '移动端自动化', eyebrow: 'Appium', description: '统一管理设备、应用包、移动端步骤和执行产物。', action: '新建设备用例', ...defaults },
  plugins: { key: 'plugins', title: '插件中心', eyebrow: '扩展能力', description: '按需安装可信扩展，为平台接入新的执行器和企业系统。', action: '上传插件', ...defaults },
}
