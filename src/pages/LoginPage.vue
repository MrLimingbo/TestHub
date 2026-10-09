<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const session = useSessionStore()
const loading = ref(false)
const form = reactive({ account: '', password: '', email: '', displayName: '', confirmPassword: '' })
const registrationEnabled = ref(false)
const registering = ref(false)

onMounted(async () => {
  try {
    const response = await fetch('/api/v1/auth/capabilities', { credentials: 'include' })
    if (response.ok) {
      const body = await response.json() as { data?: { registrationEnabled?: boolean } }
      registrationEnabled.value = body.data?.registrationEnabled === true
    }
  } catch {
    // Login remains available against older servers or after transient network failure.
    registrationEnabled.value = false
  }
})

const switchMode = () => {
  registering.value = !registering.value
  form.password = ''
  form.confirmPassword = ''
}

const login = async () => {
  if (!form.account || !form.password) return
  if (loading.value) return
  if (registering.value && form.password !== form.confirmPassword) {
    Message.error('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    if (registering.value) {
      await session.register({ account: form.account, email: form.email, displayName: form.displayName, password: form.password })
    } else {
      await session.login(form.account, form.password)
    }
    await router.push('/dashboard')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : (registering.value ? '注册失败' : '登录失败'))
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
        <div class="eyebrow">{{ registering ? '创建个人账号' : '欢迎回来' }}</div>
        <h2>{{ registering ? '注册 TestHub' : '登录 TestHub' }}</h2>
        <p>{{ registering ? '创建属于你的个人工作区' : '进入你的质量工作空间' }}</p>
        <a-form :model="form" layout="vertical" @submit-success="login">
          <a-form-item field="account" label="账号" :rules="[{ required: true, message: '请输入账号' }]"><a-input v-model="form.account" size="large" :placeholder="registering ? '3–64 位字母、数字、点、下划线或短横线' : '请输入账号或邮箱'" :max-length="registering ? 64 : 190"><template #prefix><icon-user /></template></a-input></a-form-item>
          <a-form-item v-if="registering" field="email" label="邮箱" :rules="[{ required: true, type: 'email', message: '请输入有效邮箱' }]"><a-input v-model="form.email" size="large" placeholder="请输入邮箱" /></a-form-item>
          <a-form-item v-if="registering" field="displayName" label="显示名称"><a-input v-model="form.displayName" size="large" placeholder="选填，默认使用账号名称" :max-length="80" /></a-form-item>
          <a-form-item field="password" label="密码" :rules="[{ required: true, message: '请输入密码' }]"><a-input-password v-model="form.password" size="large" placeholder="请输入密码"><template #prefix><icon-lock /></template></a-input-password></a-form-item>
          <a-form-item v-if="registering" field="confirmPassword" label="确认密码"><a-input-password v-model="form.confirmPassword" size="large" placeholder="请再次输入密码（至少 8 个字符）" /></a-form-item>
          <a-button type="primary" html-type="submit" long size="large" :loading="loading">{{ registering ? '注册并进入工作区' : '进入工作空间' }}</a-button>
        </a-form>
        <div v-if="registrationEnabled" class="login-options"><span>{{ registering ? '已有账号？' : '还没有账号？' }}</span><a-link :disabled="loading" @click="switchMode">{{ registering ? '返回登录' : '注册个人账号' }}</a-link></div>
      </div>
      <footer>© 2026 TestHub · 企业级一站式测试开发平台</footer>
    </section>
  </main>
</template>

<style scoped>
.login-options { margin: 16px 0 20px; }
</style>
