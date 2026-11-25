<template>
  <section class="ai-testing-layout">
    <nav aria-label="AI测试子菜单"><router-link to="/ai-testing/agents"><icon-robot />智能体</router-link></nav>
    <router-view />
  </section>
</template>

<style scoped>
.ai-testing-layout{min-height:100%;display:flex;flex-direction:column}
nav{display:flex;padding:0 24px;border-bottom:1px solid var(--color-border-2);background:var(--color-bg-2)}
nav a{display:inline-flex;align-items:center;gap:8px;padding:11px 13px;border-bottom:2px solid transparent;color:var(--color-text-2);text-decoration:none}
nav a.router-link-active{border-color:rgb(var(--primary-6));color:rgb(var(--primary-6));font-weight:600}
</style>
