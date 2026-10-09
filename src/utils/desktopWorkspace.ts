export interface LocalEngineStatus { phase: string; message: string; receivedBytes: number; totalBytes: number }
interface WorkspaceBridge {
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
export async function openAccountTransfer() { await bridge()?.openTransfer?.() }
export async function openTaskSync() {
  if (!bridge()?.openTaskSync) throw new Error('当前桌面版本不支持任务登记同步，请更新 TestHub。')
  await bridge()?.openTaskSync?.()
}
export async function openSettingsSync() {
  if (!bridge()?.openSettingsSync) throw new Error('当前桌面版本不支持项目配置同步，请更新 TestHub。')
  await bridge()?.openSettingsSync?.()
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
