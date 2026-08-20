import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/pages/LoginPage.vue'), meta: { public: true } },
    { path: '/mock/share/:shareKey', component: () => import('@/pages/MockSharePage.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('@/pages/DashboardPage.vue'), meta: { projectRequired: true } },
        {
          path: 'functional',
          component: () => import('@/layouts/FunctionalTestingLayout.vue'),
          meta: { projectRequired: true },
          children: [
            { path: '', component: () => import('@/pages/FunctionalOverviewPage.vue') },
            { path: 'requirements', component: () => import('@/pages/FunctionalTestingPage.vue'), props: { assetType: 'requirements' } },
            { path: 'cases', component: () => import('@/pages/FunctionalTestingPage.vue'), props: { assetType: 'cases' } },
            { path: 'plans', component: () => import('@/pages/FunctionalPlansPage.vue') },
            { path: 'governance', component: () => import('@/pages/FunctionalGovernancePage.vue') },
            { path: 'delivery', component: () => import('@/pages/FunctionalDeliveryPage.vue') },
            { path: 'versions', component: () => import('@/pages/ProjectVersionsPage.vue') },
          ],
        },
        {
          path: 'api-testing',
          component: () => import('@/layouts/ApiTestingLayout.vue'),
          meta: { projectRequired: true },
          children: [
            { path: '', redirect: '/api-testing/interfaces' },
            { path: 'interfaces', component: () => import('@/pages/ApiDesignPage.vue') },
            { path: 'quick-request', component: () => import('@/pages/ApiDesignPage.vue') },
          ],
        },
        {
          path: 'api-design',
          redirect: (to) => ({ path: '/api-testing/interfaces', query: to.query, hash: to.hash }),
          meta: { projectRequired: true },
        },
        { path: 'ui-automation', component: () => import('@/pages/UiAutomationPage.vue'), meta: { projectRequired: true } },
        { path: 'mobile-automation', component: () => import('@/pages/MobileAutomationPage.vue'), meta: { projectRequired: true } },
        { path: 'plugins', component: () => import('@/pages/PluginsPage.vue') },
        { path: 'operations', component: () => import('@/pages/OperationsPage.vue') },
        { path: 'mock', component: () => import('@/pages/MockServicePage.vue'), meta: { projectRequired: true } },
        {
          path: 'api-debug',
          redirect: (to) => ({ path: '/api-testing/quick-request', query: to.query, hash: to.hash }),
          meta: { projectRequired: true },
        },
        { path: 'api-automation', component: () => import('@/pages/AutomationPage.vue'), meta: { projectRequired: true } },
        { path: 'performance', component: () => import('@/pages/PerformancePage.vue'), meta: { projectRequired: true } },
        { path: 'reports', component: () => import('@/pages/ReportsPage.vue'), meta: { projectRequired: true } },
        { path: 'project-settings', component: () => import('@/pages/ProjectSettingsPage.vue') },
        { path: 'project-settings/members', component: () => import('@/pages/ProjectSettingsPage.vue') },
        { path: 'project-settings/environments', component: () => import('@/pages/ProjectSettingsPage.vue') },
        { path: 'project-settings/files', component: () => import('@/pages/ProjectSettingsPage.vue') },
        { path: 'project-settings/logs', component: () => import('@/pages/ProjectSettingsPage.vue') },
        { path: 'project-settings/parameters', redirect: '/project-settings/environments' },
        { path: 'project-settings/secrets', redirect: '/project-settings/environments' },
        {
          path: 'system-settings',
          component: () => import('@/layouts/SystemSettingsLayout.vue'),
          children: [
            { path: '', redirect: '/system-settings/accounts' },
            { path: 'accounts', component: () => import('@/pages/AccountManagementPage.vue') },
            { path: 'environments', component: () => import('@/pages/EnvironmentManagementPage.vue') },
            { path: 'parameters', component: () => import('@/pages/SystemSettingsPage.vue') },
          ],
        },
        { path: 'audit', component: () => import('@/pages/AuditLogPage.vue') },
        { path: 'integrations', component: () => import('@/pages/NotificationTemplatesPage.vue') },
        { path: 'tasks', component: () => import('@/pages/TasksPage.vue'), meta: { projectRequired: true } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  await session.initialize()
  if (!to.meta.public && !session.signedIn) return '/login'
  if (to.path === '/login' && session.signedIn) return '/dashboard'
  if (to.meta.projectRequired && !session.projectId) return '/project-settings?required=1'
})

export default router
