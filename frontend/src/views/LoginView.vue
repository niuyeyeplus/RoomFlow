<script setup lang="ts">
// 登录 / 注册双模式页
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/client'
import { friendlyMessage } from '@/utils/errors'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const formRef = ref<FormInstance>()
const submitting = ref(false)
const serverErrors = reactive<Record<string, string>>({})

const form = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,50}$/

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名最长50位', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (mode.value === 'register' && !USERNAME_PATTERN.test(value)) {
          callback(new Error('用户名为3-50位，仅限字母、数字、下划线'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value.length > 64) {
          callback(new Error('密码最长64位'))
        } else if (mode.value === 'register' && value.length < 8) {
          callback(new Error('密码至少8位'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    {
      validator: (_rule, value: string, callback) => {
        if (mode.value !== 'register') {
          callback()
        } else if (!value) {
          callback(new Error('请再次输入密码'))
        } else if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

watch(mode, () => {
  Object.keys(serverErrors).forEach((k) => delete serverErrors[k])
  formRef.value?.clearValidate()
})

/** 提交前的显式校验：错误写入 serverErrors，经 el-form-item :error 展示 */
function runLocalValidation(): boolean {
  Object.keys(serverErrors).forEach((k) => delete serverErrors[k])
  let ok = true
  if (!form.username.trim()) {
    serverErrors.username = '请输入用户名'
    ok = false
  } else if (form.username.trim().length > 50) {
    serverErrors.username = '用户名最长50位'
    ok = false
  } else if (mode.value === 'register' && !USERNAME_PATTERN.test(form.username.trim())) {
    serverErrors.username = '用户名为3-50位，仅限字母、数字、下划线'
    ok = false
  }
  if (!form.password) {
    serverErrors.password = '请输入密码'
    ok = false
  } else if (form.password.length > 64) {
    serverErrors.password = '密码最长64位'
    ok = false
  } else if (mode.value === 'register' && form.password.length < 8) {
    serverErrors.password = '密码至少8位'
    ok = false
  }
  if (mode.value === 'register') {
    if (!form.confirmPassword) {
      serverErrors.confirmPassword = '请再次输入密码'
      ok = false
    } else if (form.confirmPassword !== form.password) {
      serverErrors.confirmPassword = '两次输入的密码不一致'
      ok = false
    }
  }
  return ok
}

async function submit(): Promise<void> {
  // 浏览器环境下同步触发 EP 校验渲染；提交门控以显式校验为准
  formRef.value?.validate().catch(() => false)
  if (!runLocalValidation()) return
  submitting.value = true
  try {
    if (mode.value === 'login') {
      await authStore.login({ username: form.username.trim(), password: form.password })
      ElMessage.success('登录成功')
    } else {
      await authStore.register({ username: form.username.trim(), password: form.password })
      ElMessage.success('注册成功，已自动登录')
    }
    // 仅允许站内路径回跳，防开放式重定向
    const q = route.query.redirect
    const redirect = typeof q === 'string' && q.startsWith('/') && !q.startsWith('//') ? q : '/'
    await router.push(redirect)
  } catch (e) {
    if (e instanceof ApiError && e.code === 40001 && e.fieldErrors.length > 0) {
      for (const fe of e.fieldErrors) {
        serverErrors[fe.field] = fe.message
      }
    } else if (e instanceof ApiError && e.code === 40901) {
      // 用户名已存在：字段级提示
      serverErrors.username = friendlyMessage(e)
    } else {
      ElMessage.error(friendlyMessage(e))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">
        <h1>RoomFlow</h1>
        <p>会议室预约系统</p>
      </div>
      <el-tabs v-model="mode" stretch>
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
        <el-form-item prop="username" :error="serverErrors.username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            autocomplete="username"
            :prefix-icon="'User'"
          />
        </el-form-item>
        <el-form-item prop="password" :error="serverErrors.password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :prefix-icon="'Lock'"
          />
        </el-form-item>
        <el-form-item
          v-if="mode === 'register'"
          prop="confirmPassword"
          :error="serverErrors.confirmPassword"
        >
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="确认密码"
            show-password
            autocomplete="new-password"
            :prefix-icon="'Lock'"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" :loading="submitting" class="submit-btn">
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  padding: 16px;
}
.login-card {
  width: 400px;
  max-width: 100%;
}
.brand {
  text-align: center;
  margin-bottom: 8px;
}
.brand h1 {
  margin: 0;
  font-size: 26px;
}
.brand p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.submit-btn {
  width: 100%;
}
</style>
