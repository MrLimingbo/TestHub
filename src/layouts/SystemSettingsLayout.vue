<script setup lang="ts">
const menus = [
  { path: '/system-settings/accounts', label: '账号管理', icon: 'IconUserGroup' },
  { path: '/system-settings/environments', label: '环境管理', icon: 'IconCloud' },
  { path: '/system-settings/parameters', label: '系统参数', icon: 'IconSettings' },
]
</script>

<template>
  <div class="system-settings">
    <aside class="system-settings__rail">
      <div class="rail-title">
        <span class="rail-icon"><icon-apps /></span>
        <div><strong>系统设置</strong><small>平台级管理</small></div>
      </div>
      <nav aria-label="系统设置子菜单">
        <router-link v-for="menu in menus" :key="menu.path" :to="menu.path">
          <component :is="menu.icon" />
          <span>{{ menu.label }}</span>
          <icon-right />
        </router-link>
      </nav>
    </aside>
    <main class="system-settings__content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.system-settings{display:grid;grid-template-columns:230px minmax(0,1fr);min-height:100%;background:var(--color-fill-1)}
.system-settings__rail{padding:22px 14px;border-right:1px solid var(--color-border-2);background:var(--color-bg-2)}
.rail-title{display:flex;align-items:center;gap:11px;padding:0 10px 20px;border-bottom:1px solid var(--color-border-2)}
.rail-title>div{display:grid;gap:3px}.rail-title strong{font-size:15px}.rail-title small{color:var(--color-text-3);font-size:11px}
.rail-icon{display:grid;place-items:center;width:36px;height:36px;border-radius:9px;background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-size:18px}
nav{display:grid;gap:5px;padding-top:16px}
nav a{display:grid;grid-template-columns:20px minmax(0,1fr) 16px;align-items:center;gap:9px;padding:11px 12px;border-radius:8px;color:var(--color-text-2);text-decoration:none;font-size:13px}
nav a:hover{background:var(--color-fill-2)}
nav a.router-link-active{background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-weight:600}
nav a svg:last-child{font-size:12px;color:var(--color-text-3)}
.system-settings__content{min-width:0}
@media(max-width:800px){.system-settings{grid-template-columns:1fr}.system-settings__rail{padding:12px;border-right:0;border-bottom:1px solid var(--color-border-2)}.rail-title{display:none}nav{grid-template-columns:repeat(3,1fr);padding:0}}
</style>
