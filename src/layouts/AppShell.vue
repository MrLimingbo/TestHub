<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { isLocalDesktop, connectAccountService, openLocalExecutionSettings, openLocalBackup, isAccountDesktop, openAccountTransfer } from '@/utils/desktopWorkspace'
const localWorkspace = isLocalDesktop()

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const collapsed = ref(false)

const sections = [
  { label: '总览', items: [{ path: '/dashboard', label: '工作台', icon: 'IconDashboard' }] },
  { label: '测试工作', items: [
    { path: '/functional', label: '功能测试', icon: 'IconSafe' },
    { path: '/api-testing', label: '接口测试', icon: 'IconThunderbolt' },
    { path: '/api-automation', label: '接口自动化', icon: 'IconCode' },
    { path: '/mock', label: 'Mock 服务', icon: 'IconCloud' },
    { path: '/performance', label: '性能测试', icon: 'IconFire' },
    { path: '/ui-automation', label: 'Web UI 自动化', icon: 'IconComputer' },
    { path: '/mobile-automation', label: '移动端自动化', icon: 'IconMobile' },
  ] },
  { label: '洞察与管理', items: [
    { path: '/reports', label: '报表统计', icon: 'IconBarChart' },
    { path: '/tasks', label: '任务中心', icon: 'IconCalendar' },
    { path: '/project-settings', label: '项目设置', icon: 'IconSettings' },
    { path: '/system-settings', label: '系统设置', icon: 'IconApps' },
    { path: '/audit', label: '权限与审计', icon: 'IconLock' },
    { path: '/integrations', label: '通知与集成', icon: 'IconLink' },
    { path: '/plugins', label: '插件中心', icon: 'IconStorage' },
    { path: '/operations', label: '运行与发布', icon: 'IconSettings' },
  ] },
]

const title = computed(() => sections.flatMap((section) => section.items)
  .find((item) => route.path === item.path || route.path.startsWith(`${item.path}/`))?.label ?? 'TestHub')
const logout = async () => { await session.signOut(); await router.push('/login') }
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <aside class="sidebar">
      <div class="brand" @click="router.push('/dashboard')">
        <div class="brand-mark">T</div>
        <div v-if="!collapsed" class="brand-copy"><strong>TestHub</strong><span>{{ localWorkspace ? '本地工作区' : 'Quality Cloud' }}</span></div>
      </div>
      <nav class="side-nav" aria-label="主导航">
        <section v-for="section in sections" :key="section.label">
          <div v-if="!collapsed" class="nav-section-label">{{ section.label }}</div>
          <router-link v-for="item in section.items" :key="item.path" :to="item.path" class="nav-item" :title="item.label">
            <component :is="item.icon" :size="18" /><span v-if="!collapsed">{{ item.label }}</span>
          </router-link>
        </section>
      </nav>
      <button class="collapse-button" aria-label="折叠菜单" @click="collapsed = !collapsed">
        <icon-menu-fold v-if="!collapsed" /><icon-menu-unfold v-else />
      </button>
    </aside>
    <main class="workspace">
      <header class="topbar">
        <div class="topbar-title"><strong>{{ title }}</strong></div>
        <div class="topbar-actions">
          <a-select :model-value="session.projectId" size="small" :style="{ width: '190px' }" placeholder="请先选择项目" aria-label="选择项目" @change="session.selectProject(String($event))">
            <a-option v-for="project in session.projects" :key="project.id" :value="project.id">{{ project.name }}</a-option>
          </a-select>
          <a-button shape="circle"><icon-search /></a-button>
          <a-badge :count="3" dot><a-button shape="circle"><icon-notification /></a-button></a-badge>
          <a-dropdown trigger="click">
            <button class="user-chip"><a-avatar :size="30">{{ session.profile?.displayName.slice(0, 1) ?? '用' }}</a-avatar><span>{{ session.profile?.displayName ?? '当前用户' }}</span><icon-down /></button>
            <template #content><a-doption>个人中心</a-doption><a-doption v-if="isAccountDesktop()" @click="openAccountTransfer">本地资料迁入账号</a-doption><a-doption v-if="localWorkspace" @click="openLocalBackup">本地备份与恢复</a-doption><a-doption v-if="localWorkspace" @click="openLocalExecutionSettings">本地执行设置</a-doption><a-doption v-if="localWorkspace" @click="connectAccountService">连接账号服务</a-doption><a-doption v-else @click="logout">退出登录</a-doption></template>
          </a-dropdown>
        </div>
      </header>
      <div class="page-scroll"><router-view :key="`${route.path}:${session.projectId}`" /></div>
    </main>
  </div>
</template>
