'use strict'
const form = document.querySelector('#transfer-form'), source = document.querySelector('#source'), target = document.querySelector('#target')
const prepare = document.querySelector('#prepare'), commit = document.querySelector('#commit'), preview = document.querySelector('#preview'), errorElement = document.querySelector('#error')
const includePlans = document.querySelector('#include-plans')
const includeGovernance = document.querySelector('#include-governance')
const includeDefects = document.querySelector('#include-defects')
const includeFiles = document.querySelector('#include-files')
const includeHistory = document.querySelector('#include-history')
const includeProjectVersions = document.querySelector('#include-project-versions')
const includeGovernanceCatalogs = document.querySelector('#include-governance-catalogs')
function catalogSummary(value) { return value == null ? '' : `；其中 ${value} 个治理目录` }
function versionSummary(value) { return value == null ? '' : `；${value} 个项目版本` }
function historySummary(value) { return value ? `；${value.runs} 个执行批次、${value.cases} 条执行用例、${value.steps} 条历史步骤` : '' }
function fileSummary(value) { return value ? `；${value.items} 个项目文件（${(value.bytes / 1024 / 1024).toFixed(2)} MiB）` : '' }
function defectSummary(value) { return value ? `；${value.items} 个缺陷、${value.links} 条资产关联（不迁入 ${value.omittedExecutionLinks} 条执行记录关联）` : '' }
function governanceSummary(value) { return value ? `；${value.tags} 个标签、${value.fields} 个字段定义和 ${value.bindings} 条标签关联` : '' }
let token, busy = false
function error(value) { errorElement.textContent = value.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '迁移失败，请重试。'; errorElement.hidden = false }
function controls(value) { busy = value; source.disabled = value; target.disabled = value; includePlans.disabled = value; includeGovernance.disabled = value; includeDefects.disabled = value; includeFiles.disabled = value; includeHistory.disabled = value; includeProjectVersions.disabled = value; includeGovernanceCatalogs.disabled = value; document.querySelector('#open-automation-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-mock-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-performance-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-ui-automation-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-mobile-automation-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-quality-report-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-task-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-notification-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; document.querySelector('#open-settings-transfer').disabled = value || source.options.length <= 1 || target.options.length <= 1; prepare.disabled = value; commit.disabled = value || !token }
function invalidate() { token = undefined; preview.textContent = ''; commit.disabled = true }
source.addEventListener('change', invalidate); target.addEventListener('change', invalidate); includePlans.addEventListener('change', invalidate)
includeGovernance.addEventListener('change', invalidate); includeDefects.addEventListener('change', invalidate); includeFiles.addEventListener('change', invalidate); includeHistory.addEventListener('change', invalidate); includeProjectVersions.addEventListener('change', invalidate); includeGovernanceCatalogs.addEventListener('change', invalidate)
controls(true)
window.testhubDesktop.transferContext().then(context => {
  document.querySelector('#account').textContent = `当前账号：${context.actor.name}（${context.actor.account}） · ${context.server}`
  for (const [select, projects] of [[source, context.sourceProjects], [target, context.targetProjects]]) {
    const empty = document.createElement('option'); empty.value = ''; empty.textContent = '请选择项目'; select.append(empty)
    for (const project of projects) { const option = document.createElement('option'); option.value = project.id; option.textContent = `${project.organization} / ${project.name}`; select.append(option) }
  }
}).catch(value => {
  document.querySelector('#account').textContent = '未能读取项目，请关闭迁移窗口后重试。'
  error(value)
}).finally(() => controls(false))
form.addEventListener('submit', async event => {
  event.preventDefault(); if (busy) return
  invalidate(); errorElement.hidden = true; controls(true)
  try {
    const result = await window.testhubDesktop.prepareTransfer({ sourceId: source.value, targetId: target.value, includePlans: includePlans.checked, includeGovernance: includeGovernance.checked, includeDefects: includeDefects.checked, includeFiles: includeFiles.checked, includeHistory: includeHistory.checked, includeProjectVersions: includeProjectVersions.checked, includeGovernanceCatalogs: includeGovernanceCatalogs.checked })
    token = result.token
    preview.textContent = `将复制 ${result.counts.catalogs} 个目录、${result.counts.requirements} 条需求、${result.counts.cases} 条用例和 ${result.counts.plans} 个计划至“${result.target.name}”${governanceSummary(result.counts.governance)}${defectSummary(result.counts.defects)}${fileSummary(result.counts.files)}${historySummary(result.counts.history)}${versionSummary(result.counts.projectVersions)}${catalogSummary(result.counts.governanceCatalogs)}。以当前预览的数据为准。`
  } catch (value) { error(value) } finally { controls(false) }
})
commit.addEventListener('click', async () => {
  if (busy || !token) return
  errorElement.hidden = true; controls(true)
  try {
    const result = await window.testhubDesktop.commitTransfer(token)
    if (result.cancelled) return
    token = undefined; preview.textContent = `迁移完成：${result.requirements} 条需求、${result.cases} 条用例和 ${result.plans || 0} 个计划${governanceSummary(result.governance)}${defectSummary(result.defects)}${fileSummary(result.files)}${historySummary(result.history)}${versionSummary(result.projectVersions)}${catalogSummary(result.governanceCatalogs)}。刷新账号网页即可查看。`
  } catch (value) { error(value) } finally { controls(false) }
})
