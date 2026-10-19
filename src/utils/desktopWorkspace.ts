export interface LocalEngineStatus { phase: string; message: string; receivedBytes: number; totalBytes: number }
interface WorkspaceBridge {
  openApiAutomationScenarioSync?: () => Promise<void>
  openApiAutomationHistorySync?: () => Promise<void>
  openPerformanceHistorySync?: () => Promise<void>
  openPerformanceArtifactSync?: () => Promise<void>
  openUiAutomationHistorySync?: () => Promise<void>
  openUiAutomationArtifactSync?: () => Promise<void>
  openApiAutomationTriggerSync?: () => Promise<void>
  openApiAutomationAssetSync?: () => Promise<void>
  openApiDesignSync?: () => Promise<void>
  openProjectFileSync?: () => Promise<void>
  openFunctionalHistorySync?: () => Promise<void>
  openFunctionalDefectExecutionSync?: () => Promise<void>
  openFunctionalDefectPeopleSync?: () => Promise<void>
  openFunctionalDefectWorkflowSync?: () => Promise<void>
  openFunctionalDefectLinkSync?: () => Promise<void>
  openProjectVersionSync?: () => Promise<void>
  openFunctionalDefectSync?: () => Promise<void>
  openFunctionalSync?: () => Promise<void>
  openFunctionalAssetTagSync?: () => Promise<void>
  openFunctionalGovernanceSync?: () => Promise<void>
  openMockSync?: () => Promise<void>
  openMobileAutomationSync?: () => Promise<void>
  openUiAutomationSync?: () => Promise<void>
  openPerformanceSync?: () => Promise<void>
  openNotificationSync?: () => Promise<void>
  openSettingsSync?: () => Promise<void>
  openTaskSync?: () => Promise<void>
  ensureAppium?: () => Promise<void>
  ensureBrowser?: (value: { browser: 'chromium' | 'firefox' | 'webkit'; origin: string }) => Promise<void>
  browserStatus?: () => Promise<LocalEngineStatus>
  cancelBrowserPreparation?: () => Promise<void>
  ensureJmeter?: (targets: string[]) => Promise<void>
  jmeterStatus?: () => Promise<LocalEngineStatus>
  cancelJmeterPreparation?: () => Promise<void>
  workspaceMode?: 'local' | 'server'
  resumeLocalWorkspace?: () => Promise<void>
  connectAccount?: () => Promise<void>
  openBackup?: () => Promise<void>
  openTransfer?: () => Promise<void>
  openExecutionSettings?: () => Promise<void>
}
function bridge(): WorkspaceBridge | undefined {
  return typeof window === 'undefined' ? undefined : (window as Window & { testhubDesktop?: WorkspaceBridge }).testhubDesktop
}
export function isLocalDesktop() { return bridge()?.workspaceMode === 'local' }
export function isAccountDesktop() { return bridge()?.workspaceMode === 'server' }
export async function openApiAutomationScenarioSync() {
  if (!bridge()?.openApiAutomationScenarioSync) throw new Error('当前桌面版本不支持接口自动化场景同步，请更新 TestHub。')
  await bridge()?.openApiAutomationScenarioSync?.()
}

export async function openApiAutomationHistorySync() {
  const native = bridge()
  if (!native?.openApiAutomationHistorySync) throw new Error('当前桌面版本不支持接口自动化执行历史同步')
  await native.openApiAutomationHistorySync()
}

export async function openPerformanceHistorySync() {
  const native = bridge()
  if (!native?.openPerformanceHistorySync) throw new Error('当前桌面版本不支持性能执行历史同步')
  await native.openPerformanceHistorySync()
}

export async function openPerformanceArtifactSync() {
  const native = bridge()
  if (!native?.openPerformanceArtifactSync) throw new Error('当前桌面版本不支持原始 JTL／日志同步')
  await native.openPerformanceArtifactSync()
}

export async function openUiAutomationArtifactSync() {
  const native = bridge()
  if (!native?.openUiAutomationArtifactSync) throw new Error('当前桌面版本不支持Web UI 报告产物同步')
  await native.openUiAutomationArtifactSync()
}

export async function openUiAutomationHistorySync() {
  const native = bridge()
  if (!native?.openUiAutomationHistorySync) throw new Error('当前桌面版本不支持Web UI 执行历史同步')
  await native.openUiAutomationHistorySync()
}

export async function openApiAutomationTriggerSync() {
  const native = bridge()
  if (!native?.openApiAutomationTriggerSync) throw new Error('当前桌面版本不支持接口自动化触发配置同步')
  await native.openApiAutomationTriggerSync()
}

export async function openApiAutomationAssetSync() {
  if (!bridge()?.openApiAutomationAssetSync) throw new Error('当前桌面版本不支持接口自动化基础资料同步，请更新 TestHub。')
  await bridge()?.openApiAutomationAssetSync?.()
}

export async function openApiDesignSync() {
  if (!bridge()?.openApiDesignSync) throw new Error('当前桌面版本不支持接口设计资料同步，请更新 TestHub。')
  await bridge()?.openApiDesignSync?.()
}
export async function openAccountTransfer() { await bridge()?.openTransfer?.() }
export async function openTaskSync() {
  if (!bridge()?.openTaskSync) throw new Error('当前桌面版本不支持任务登记同步，请更新 TestHub。')
  await bridge()?.openTaskSync?.()
}
export async function openSettingsSync() {
  if (!bridge()?.openSettingsSync) throw new Error('当前桌面版本不支持项目配置同步，请更新 TestHub。')
  await bridge()?.openSettingsSync?.()
}
export async function openProjectFileSync() {
  if (!bridge()?.openProjectFileSync) throw new Error('当前桌面版本不支持项目文件同步，请更新 TestHub。')
  await bridge()?.openProjectFileSync?.()
}
export async function resumeLocalWorkspace() { await bridge()?.resumeLocalWorkspace?.() }
export async function connectAccountService() { await bridge()?.connectAccount?.() }
export async function openLocalExecutionSettings() { await bridge()?.openExecutionSettings?.() }

export async function ensureLocalJmeter(targets: string[]) {
  const native = bridge()
  if (!native?.ensureJmeter) throw new Error('当前桌面版本不支持自动准备 JMeter，请更新 TestHub。')
  await native.ensureJmeter(targets)
}
export async function localJmeterStatus() { return bridge()?.jmeterStatus?.() }
export async function cancelJmeterPreparation() { await bridge()?.cancelJmeterPreparation?.() }

export async function ensureLocalBrowser(browser: 'chromium' | 'firefox' | 'webkit', origin: string) {
  const native = bridge()
  if (!native?.ensureBrowser) throw new Error('当前桌面版本不支持自动准备浏览器，请更新 TestHub。')
  await native.ensureBrowser({ browser, origin })
}
export async function localBrowserStatus() { return bridge()?.browserStatus?.() }
export async function cancelBrowserPreparation() { await bridge()?.cancelBrowserPreparation?.() }

export async function openLocalBackup() { await bridge()?.openBackup?.() }

export async function ensureLocalAppium() {
  const native = bridge()
  if (!native?.ensureAppium) throw new Error('当前桌面版本不支持本地移动端运行器，请更新 TestHub。')
  await native.ensureAppium()
}

export async function openNotificationSync() {
  if (!bridge()?.openNotificationSync) throw new Error('当前桌面版本不支持通知模板同步，请更新 TestHub。')
  await bridge()?.openNotificationSync?.()
}

export async function openMockSync() {
  if (!bridge()?.openMockSync) throw new Error('当前桌面版本不支持 Mock 同步，请更新 TestHub。')
  await bridge()?.openMockSync?.()
}

export async function openPerformanceSync() {
  if (!bridge()?.openPerformanceSync) throw new Error('当前桌面版本不支持 性能计划同步，请更新 TestHub。')
  await bridge()?.openPerformanceSync?.()
}

export async function openUiAutomationSync() {
  if (!bridge()?.openUiAutomationSync) throw new Error('当前桌面版本不支持 Web UI 资料同步，请更新 TestHub。')
  await bridge()?.openUiAutomationSync?.()
}

export async function openMobileAutomationSync() {
  if (!bridge()?.openMobileAutomationSync) throw new Error('当前桌面版本不支持 移动端 资料同步，请更新 TestHub。')
  await bridge()?.openMobileAutomationSync?.()
}

export async function openFunctionalSync() {
  if (!bridge()?.openFunctionalSync) throw new Error('当前桌面版本不支持功能测试资料同步，请更新 TestHub。')
  await bridge()?.openFunctionalSync?.()
}

export async function openFunctionalGovernanceSync() {
  if (!bridge()?.openFunctionalGovernanceSync) throw new Error('当前桌面版本不支持治理定义同步，请更新 TestHub。')
  await bridge()?.openFunctionalGovernanceSync?.()
}

export async function openFunctionalAssetTagSync() {
  if (!bridge()?.openFunctionalAssetTagSync) throw new Error('当前桌面版本不支持标签关联同步，请更新 TestHub。')
  await bridge()?.openFunctionalAssetTagSync?.()
}

export async function openFunctionalDefectLinkSync() {
  if (!bridge()?.openFunctionalDefectLinkSync) throw new Error('当前桌面版本不支持缺陷关联同步，请更新 TestHub。')
  await bridge()?.openFunctionalDefectLinkSync?.()
}

export async function openProjectVersionSync() {
  if (!bridge()?.openProjectVersionSync) throw new Error('当前桌面版本不支持版本与构建同步，请更新 TestHub。')
  await bridge()?.openProjectVersionSync?.()
}

export async function openFunctionalDefectSync() {
  if (!bridge()?.openFunctionalDefectSync) throw new Error('当前桌面版本不支持缺陷资料同步，请更新 TestHub。')
  await bridge()?.openFunctionalDefectSync?.()
}

export async function openFunctionalDefectWorkflowSync() {
  if (!bridge()?.openFunctionalDefectWorkflowSync) throw new Error('当前桌面版本不支持缺陷工作流同步，请更新 TestHub。')
  await bridge()?.openFunctionalDefectWorkflowSync?.()
}

export async function openFunctionalDefectPeopleSync() {
  if (!bridge()?.openFunctionalDefectPeopleSync) throw new Error('当前桌面版本不支持缺陷人员同步，请更新 TestHub。')
  await bridge()?.openFunctionalDefectPeopleSync?.()
}

export async function openFunctionalHistorySync() {
  if (!bridge()?.openFunctionalHistorySync) throw new Error('当前桌面版本不支持执行历史同步，请更新 TestHub。')
  await bridge()?.openFunctionalHistorySync?.()
}

export async function openFunctionalDefectExecutionSync() {
  if (!bridge()?.openFunctionalDefectExecutionSync) throw new Error('当前桌面版本不支持缺陷执行关联同步，请更新 TestHub。')
  await bridge()?.openFunctionalDefectExecutionSync?.()
}
