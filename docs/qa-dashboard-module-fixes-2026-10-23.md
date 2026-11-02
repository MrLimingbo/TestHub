# 工作台模块四项缺陷修复

2026-10-23。已修复并完成后台回归，待用户审核。

## QA-DASH-001 · 统一上海自然日分组

按上海自然日计算每个桶的结束 Instant，与汇总查询使用相同 JDBC Timestamp 边界；SQL CASE 分桶后仍在数据库聚合，避免数据库 DATE 的本地时区差异。保留原三参数查询方法，质量报表调用兼容。 [ProjectDashboardRepository.java:147](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/dashboard/ProjectDashboardRepository.java:147)

## QA-DASH-002 · 屏蔽过期周期响应

每次加载递增请求序号，仅当前请求可以写入数据、错误提示和 loading；页面卸载后使旧请求失效，也防止上下文晚到后再发起看板请求。 [DashboardPage.vue:109](/Users/limingbo/Documents/codeXProject/testHub/src/pages/DashboardPage.vue:109)

## QA-DASH-003 · 非法参数返回客户端错误

为 MethodArgumentTypeMismatchException 增加共享处理，返回 HTTP400、REQUEST_REJECTED、requestId 和 details；文案不回显输入内容。新增参数化测试还覆盖负向整数溢出。 [ApiExceptionHandler.java:61](/Users/limingbo/Documents/codeXProject/testHub/services/platform-api/src/main/java/io/testhub/platform/shared/api/ApiExceptionHandler.java:61)

## QA-DASH-004 · 图表随容器尺寸调整

ResizeObserver 监听图表容器并调用 ECharts resize，同时保留 window resize 监听；卸载时移除监听、断开观察器并销毁图表。 [DashboardTrendChart.vue:58](/Users/limingbo/Documents/codeXProject/testHub/src/components/DashboardTrendChart.vue:58)

## 验证

原工作台103/103场景通过，原失败8/8重复确认；前端399/399，隔离后端752/752，lint、类型检查及构建通过。源码新增前端7条、后端13条均包含在对应全量数量中。

完整统计、限制及初测超时处理见[测试报告](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-02-dashboard/TEST-REPORT.md)；[缺陷详细步骤与复验](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261023/module-02-dashboard/BUGS.md)。未安装/发布应用，下一模块未开始。