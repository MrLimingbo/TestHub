<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const session = useSessionStore()
const loading = ref(false)
const form = reactive({ account: 'admin', password: 'TestHub2026!' })

const login = async () => {
  if (!form.account || !form.password) return
  loading.value = true
  try {
    await session.login(form.account, form.password)
    await router.push('/dashboard')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-showcase">
      <div class="login-brand"><div class="brand-mark brand-mark--light">T</div><strong>TestHub</strong></div>
      <div class="showcase-copy">
        <span class="showcase-kicker">QUALITY ENGINEERING CLOUD</span>
        <h1>让每一次交付<br />都有质量答案</h1>
        <p>设计、调试、自动化、性能与质量洞察，汇聚在一个为测试团队打造的工作空间。</p>
      </div>
      <div class="showcase-grid">
        <div><strong>96.8%</strong><span>本周测试通过率</span></div>
        <div><strong>2,418</strong><span>自动化执行次数</span></div>
        <div><strong>18m</strong><span>平均反馈时间</span></div>
      </div>
      <div class="orbit orbit-one"></div><div class="orbit orbit-two"></div>
    </section>
    <section class="login-panel">
      <div class="login-form">
        <div class="eyebrow">欢迎回来</div>
        <h2>登录 TestHub</h2>
        <p>进入星云科技的质量工作空间</p>
        <a-form :model="form" layout="vertical">
          <a-form-item field="account" label="账号"><a-input v-model="form.account" size="large" placeholder="请输入账号或邮箱"><template #prefix><icon-user /></template></a-input></a-form-item>
          <a-form-item field="password" label="密码"><a-input-password v-model="form.password" size="large" placeholder="请输入密码"><template #prefix><icon-lock /></template></a-input-password></a-form-item>
          <div class="login-options"><a-checkbox default-checked>保持登录</a-checkbox><a-link>忘记密码？</a-link></div>
          <a-button type="primary" html-type="button" long size="large" :loading="loading" @click="login">进入工作空间</a-button>
        </a-form>
        <div class="demo-hint"><icon-info-circle /> 初始账号：admin / TestHub2026!</div>
      </div>
      <footer>© 2026 TestHub · 企业级一站式测试开发平台</footer>
    </section>
  </main>
</template>
