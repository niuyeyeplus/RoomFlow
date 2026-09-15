<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import { useAuth } from '@/composables/useAuth'
import { friendlyMessage } from '@/utils/errors'

const route = useRoute()
const authStore = useAuthStore()
const notificationStore = useNotificationStore()
const { isAuthenticated } = storeToRefs(authStore)
const { unreadCount } = storeToRefs(notificationStore)
const { user, logout } = useAuth()

const showNav = computed(() => isAuthenticated.value && route.name !== 'login')

onMounted(() => {
  if (isAuthenticated.value) notificationStore.fetchUnreadCount()
})

watch(isAuthenticated, (v) => {
  if (v) notificationStore.fetchUnreadCount()
})

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '退出登录', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await logout()
    ElMessage.success('已退出登录')
  } catch (e) {
    // 本地态已清理并完成跳转；此处仅提示服务端登出失败
    ElMessage.error(friendlyMessage(e))
  }
}
</script>

<template>
  <el-container class="app-shell">
    <el-header v-if="showNav" class="app-header">
      <div class="brand">RoomFlow</div>
      <el-menu :default-active="route.path" mode="horizontal" router class="nav-menu">
        <el-menu-item index="/meetings">会议列表</el-menu-item>
        <el-menu-item index="/rooms">会议室</el-menu-item>
        <el-menu-item index="/my-meetings">我的会议</el-menu-item>
      </el-menu>
      <div class="header-right">
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" class="bell">
          <el-button text @click="$router.push('/notifications')">
            <el-icon :size="18"><Bell /></el-icon>
          </el-button>
        </el-badge>
        <el-dropdown>
          <span class="username">{{ user?.username }}</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main class="app-main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style>
body {
  margin: 0;
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.app-shell {
  min-height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-light);
  padding: 0 20px;
  gap: 24px;
}
.brand {
  font-size: 18px;
  font-weight: 700;
  color: var(--el-color-primary);
  white-space: nowrap;
}
.nav-menu {
  flex: 1;
  border-bottom: none;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.username {
  cursor: pointer;
  color: var(--el-text-color-primary);
}
.app-main {
  padding: 0;
}
</style>
