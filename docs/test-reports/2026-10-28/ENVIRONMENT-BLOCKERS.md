# 环境阻塞清单

共12条未通过环境验证的用例，涉及7组环境条件；移动端真实设备执行为0。观察和模拟验证不能替代这些原生验收。

|编号|模块|用例|检查目标|环境组|来源|
|---|---|---|---|---|---|
|BLOCK-12-D001|移动端自动化|D001|Android设备七种步骤及PNG|ENV-ANDROID|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)|
|BLOCK-12-D002|移动端自动化|D002|iOS设备七种步骤及PNG|ENV-IOS|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)|
|BLOCK-12-D003|移动端自动化|D003|真实设备断开与授权错误|ENV-ANDROID, ENV-IOS|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)|
|BLOCK-12-D004|移动端自动化|D004|真实设备超时取消与会话释放|ENV-ANDROID, ENV-IOS|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261027/module-12-mobile-regression/execution-final.json)|
|BLOCK-15-F016|桌面工作区与跨端|F016|大小写重名拒绝|ENV-CASE-FS|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/execution-final.json)|
|BLOCK-15-X001|桌面工作区与跨端|X001|Intel Mac原生运行|ENV-INTEL|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/execution-final.json)|
|BLOCK-15-X002|桌面工作区与跨端|X002|Windows原生运行与恢复|ENV-WINDOWS|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/execution-final.json)|
|BLOCK-15-X003|桌面工作区与跨端|X003|正式安装及Gatekeeper首启|ENV-CLEAN-INSTALL|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-15-desktop-workspace-cross-end/execution-final.json)|
|BLOCK-16-Z001|横向回归|Z001|Intel Mac原生包及运行|ENV-INTEL|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|
|BLOCK-16-Z002|横向回归|Z002|Windows原生安装与运行|ENV-WINDOWS|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|
|BLOCK-16-Z003|横向回归|Z003|发行者签名与公证|ENV-SIGNING|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|
|BLOCK-16-Z004|横向回归|Z004|Finder/Gatekeeper干净机器安装首启|ENV-CLEAN-INSTALL|[原始阻塞](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261028/module-16-cross-regression-fixed/execution-final.json)|

## 补齐条件

|环境组|关联用例|条件|
|---|---|---|
|Android设备或模拟器|12/D001, 12/D003, 12/D004|可用adb、Appium及已授权Android设备/模拟器，实际七步、PNG、断开、超时、取消与会话释放。|
|iOS真机或模拟器|12/D002, 12/D003, 12/D004|可用Xcode/simctl、Appium和iOS设备条件，实际执行及错误恢复。|
|大小写敏感文件系统|15/F016|在独占、大小写敏感卷复核认证清单重名拒绝；现有归档单元测试不能替代该原生场景。|
|Intel Mac原生环境|15/X001, 16/Z001|在Intel原生系统准备、校验、运行目标架构包并执行工作区流程。|
|Windows原生环境|15/X002, 16/Z002|原生Windows安装、首启、PowerShell、运行时、恢复及跨系统工作区验证。|
|发行者签名与公证|16/Z003|具备发行者证书和公证条件后核验正式分发包，当前ad-hoc不能代替。|
|干净机器Finder/Gatekeeper首启|15/X003, 16/Z004|用最终发行包在干净机器验证安装、隔离标记和Finder/Gatekeeper首启。|
