<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface Account {
  id: string
  account: string
  displayName: string
  email: string
  phone: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}
interface AccountPage {
  items: Account[]
  total: number
  page: number
  pageSize: number
}

const session = useSessionStore()
const accounts = ref<Account[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const editingId = ref<string | null>(null)
const passwordAccount = ref<Account | null>(null)
const keyword = ref('')
const pagination = reactive({ current: 1, pageSize: 10, total: 0 })
const form = reactive({ account: '', displayName: '', email: '', phone: '', enabled: true })
const passwordForm = reactive({ password: '', confirmPassword: '' })

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: String(pagination.current), pageSize: String(pagination.pageSize) })
    if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
    const result = await read<AccountPage>(`/api/v1/accounts?${params}`)
    accounts.value = result.items
    pagination.current = result.page
    pagination.pageSize = result.pageSize
    pagination.total = result.total
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载账号失败')
  } finally {
    loading.value = false
  }
}

async function search() {
  pagination.current = 1
  await load()
}

async function changePage(page: number) {
  pagination.current = page
  await load()
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { account: '', displayName: '', email: '', phone: '', enabled: true })
  dialogVisible.value = true
}

function openEdit(account: Account) {
  editingId.value = account.id
  Object.assign(form, {
    account: account.account,
    displayName: account.displayName,
    email: account.email,
    phone: account.phone ?? '',
    enabled: account.enabled,
  })
  dialogVisible.value = true
}

async function save(): Promise<boolean> {
  if (!form.account.trim()) {
    Message.warning('请输入账号')
    return false
  }
  saving.value = true
  try {
    const url = editingId.value ? `/api/v1/accounts/${editingId.value}` : '/api/v1/accounts'
    await mutate<Account>(url, editingId.value ? 'PUT' : 'POST', form)
    dialogVisible.value = false
    pagination.current = 1
    await load()
    Message.success(editingId.value ? '账号已更新' : '账号已创建，默认密码为 admin')
    return true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '保存账号失败')
    return false
  } finally {
    saving.value = false
  }
}

async function remove(account: Account) {
  try {
    await mutate<void>(`/api/v1/accounts/${account.id}`, 'DELETE')
    if (accounts.value.length === 1 && pagination.current > 1) pagination.current -= 1
    await load()
    Message.success('账号已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除账号失败')
  }
}

function openPassword(account: Account) {
  passwordAccount.value = account
  passwordForm.password = ''
  passwordForm.confirmPassword = ''
  passwordDialogVisible.value = true
}

async function savePassword(): Promise<boolean> {
  if (passwordForm.password.length < 5 || passwordForm.password.length > 72
    || new TextEncoder().encode(passwordForm.password).length > 72) {
    Message.warning('密码长度应为 5 到 72 个字符，且不能超过 72 字节')
    return false
  }
  if (passwordForm.password !== passwordForm.confirmPassword) {
    Message.warning('两次输入的密码不一致')
    return false
  }
  if (!passwordAccount.value) return false
  saving.value = true
  try {
    await mutate<void>(`/api/v1/accounts/${passwordAccount.value.id}/password`, 'PUT', {
      password: passwordForm.password,
    })
    passwordDialogVisible.value = false
    Message.success(`账号“${passwordAccount.value.account}”的密码已修改`)
    return true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '修改密码失败')
    return false
  } finally {
    saving.value = false
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

onMounted(load)
</script>

<template>
  <div class="page account-page">
    <section class="panel account-panel">
      <div class="account-toolbar">
        <div>
          <strong>账号列表</strong>
          <span>共 {{ pagination.total }} 个账号</span>
        </div>
        <div class="account-toolbar__actions">
          <a-input-search
            v-model="keyword"
            :style="{ width: '280px' }"
            allow-clear
            placeholder="按账号、姓名、邮箱或电话搜索"
            search-button
            @search="search"
            @clear="search"
            @press-enter="search"
          />
          <a-button type="primary" @click="openCreate"><icon-plus /> 新增账号</a-button>
        </div>
      </div>
      <a-spin :loading="loading" class="account-spin">
        <a-table :data="accounts" :pagination="false" :scroll="{ x: 1235 }" row-key="id">
          <template #columns>
            <a-table-column title="账号" data-index="account" :width="180">
              <template #cell="{ record }"><div class="account-cell"><a-avatar>{{ record.displayName.slice(0, 1) }}</a-avatar><strong>{{ record.account }}</strong></div></template>
            </a-table-column>
            <a-table-column title="姓名" data-index="displayName" :width="130" />
            <a-table-column title="邮箱" data-index="email" :width="250" />
            <a-table-column title="电话" :width="150"><template #cell="{ record }">{{ record.phone || '—' }}</template></a-table-column>
            <a-table-column title="状态" :width="100">
              <template #cell="{ record }"><a-tag :color="record.enabled ? 'green' : 'gray'">{{ record.enabled ? '启用' : '停用' }}</a-tag></template>
            </a-table-column>
            <a-table-column title="创建时间" :width="175"><template #cell="{ record }">{{ formatDate(record.createdAt) }}</template></a-table-column>
            <a-table-column title="操作" :width="230" fixed="right">
              <template #cell="{ record }">
                <a-space>
                  <a-button type="text" @click="openEdit(record)">编辑</a-button>
                  <a-button type="text" @click="openPassword(record)">修改密码</a-button>
                  <a-popconfirm
                    :content="record.id === session.profile?.id ? '不能删除当前登录账号' : `确定删除账号“${record.account}”吗？`"
                    :disabled="record.id === session.profile?.id"
                    @ok="remove(record)"
                  >
                    <a-button type="text" status="danger" :disabled="record.id === session.profile?.id">删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </a-table-column>
          </template>
        </a-table>
        <a-empty v-if="!loading && accounts.length === 0" description="暂无符合条件的账号" />
      </a-spin>
      <div class="account-footer">
        <p>查看并维护当前系统全部账号。新建账号的初始密码统一为 admin，建议账号首次登录后及时修改。</p>
        <a-pagination
          :current="pagination.current"
          :page-size="pagination.pageSize"
          :total="pagination.total"
          :hide-on-single-page="false"
          @change="changePage"
        />
      </div>
    </section>

    <a-modal
      v-model:visible="dialogVisible"
      :title="editingId ? '编辑账号' : '新增账号'"
      width="540px"
      :ok-loading="saving"
      :on-before-ok="save"
    >
      <a-alert v-if="!editingId" type="info" class="password-alert">账号创建后可使用默认密码 <strong>admin</strong> 登录。</a-alert>
      <a-form :model="form" layout="vertical">
        <div class="form-grid">
          <a-form-item label="账号" required extra="支持大小写字母、数字、下划线及常用特殊符号，不允许空格。">
            <a-input v-model="form.account" :max-length="160" placeholder="例如：tester01" />
          </a-form-item>
          <a-form-item label="姓名">
            <a-input v-model="form.displayName" :max-length="80" placeholder="未填写时使用账号" />
          </a-form-item>
          <a-form-item label="邮箱">
            <a-input v-model="form.email" :max-length="190" placeholder="未填写时自动生成系统邮箱" />
          </a-form-item>
          <a-form-item label="电话">
            <a-input v-model="form.phone" :max-length="40" placeholder="选填" />
          </a-form-item>
        </div>
        <a-form-item label="账号状态"><a-switch v-model="form.enabled" checked-text="启用" unchecked-text="停用" /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:visible="passwordDialogVisible"
      :title="`修改密码 · ${passwordAccount?.account ?? ''}`"
      width="500px"
      :ok-loading="saving"
      :on-before-ok="savePassword"
    >
      <a-alert type="warning" class="password-alert">修改后原密码立即失效，请将新密码安全地告知对应账号使用人。</a-alert>
      <a-form :model="passwordForm" layout="vertical">
        <a-form-item label="新密码" required extra="密码长度为 5 到 72 个字符，且不能超过 72 字节。">
          <a-input-password v-model="passwordForm.password" :max-length="72" placeholder="请输入新密码" />
        </a-form-item>
        <a-form-item label="确认新密码" required>
          <a-input-password v-model="passwordForm.confirmPassword" :max-length="72" placeholder="请再次输入新密码" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.account-panel{margin-top:20px;padding:0;overflow:hidden}.account-toolbar{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 20px;border-bottom:1px solid var(--color-border-2)}.account-toolbar>div{display:grid;gap:4px}.account-toolbar strong{font-size:16px}.account-toolbar span{color:var(--color-text-3);font-size:12px}.account-toolbar__actions{display:flex!important;align-items:center;gap:10px}.account-spin{display:block;min-height:360px}.account-cell{display:flex;align-items:center;gap:10px}.account-cell strong{font-size:13px}.account-footer{display:flex;align-items:center;justify-content:space-between;gap:20px;min-height:58px;padding:0 20px;border-top:1px solid var(--color-border-2)}.account-footer p{margin:0;color:var(--color-text-3);font-size:13px}.form-grid{display:grid;grid-template-columns:1fr}.password-alert{margin-bottom:18px}.account-panel :deep(.arco-table-th){background:var(--color-fill-1);color:var(--color-text-3);font-size:12px}.account-panel :deep(.arco-table-td){font-size:13px}@media(max-width:760px){.account-toolbar,.account-footer{align-items:stretch;flex-direction:column}.account-toolbar :deep(.arco-input-wrapper){width:100%!important}.account-toolbar__actions{align-items:stretch;flex-direction:column}.account-footer{padding:14px 20px}.account-footer :deep(.arco-pagination){justify-content:flex-start}}
</style>
