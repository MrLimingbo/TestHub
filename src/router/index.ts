import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const moduleRoutes = [
  ['api-design', '/api-design'],
  ['mock', '/mock'],
  ['ui-automation', '/ui-automation'],
  ['mobile-automation', '/mobile-automation'],
  ['plugins', '/plugins'],
].map(([moduleKey, path]) => ({ path, component: () => import('@/pages/ModulePage.vue'), props: { moduleKey } }))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/pages/LoginPage.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('@/pages/DashboardPage.vue') },
        { path: 'functional', component: () => import('@/pages/FunctionalTestingPage.vue') },
        ...moduleRoutes,
        { path: 'api-debug', component: () => import('@/pages/ApiDebugPage.vue') },
        { path: 'api-automation', component: () => import('@/pages/AutomationPage.vue') },
        { path: 'performance', component: () => import('@/pages/PerformancePage.vue') },
        { path: 'reports', component: () => import('@/pages/ReportsPage.vue') },
        { path: 'project-settings', component: () => import('@/pages/ProjectAccessPage.vue') },
        { path: 'project-settings/parameters', component: () => import('@/pages/ProjectParameterPage.vue') },
        { path: 'project-settings/secrets', component: () => import('@/pages/EnvironmentSecretsPage.vue') },
        { path: 'project-settings/files', component: () => import('@/pages/ProjectFilesPage.vue') },
        { path: 'system-settings', component: () => import('@/pages/SystemSettingsPage.vue') },
        { path: 'audit', component: () => import('@/pages/AuditLogPage.vue') },
        { path: 'integrations', component: () => import('@/pages/NotificationTemplatesPage.vue') },
        { path: 'tasks', component: () => import('@/pages/TasksPage.vue') },
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
})

export default router
