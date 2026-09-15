<script setup lang="ts">
// 站内通知：未读优先列表、单条/全部已读、跳转关联会议
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useNotificationStore } from '@/stores/notification'
import EmptyState from '@/components/EmptyState.vue'
import { friendlyMessage } from '@/utils/errors'
import { NOTIFICATION_TYPE_TEXT } from '@/utils/format'
import { formatDateTime } from '@/utils/time'
import type { NotificationVO } from '@/types/api'

const router = useRouter()
const store = useNotificationStore()
const { records, total, page, size, loading, error } = storeToRefs(store)

type ReadFilter = '' | 'false' | 'true'
const readFilter = ref<ReadFilter>('')
const markingAll = ref(false)
const markingId = ref<number | null>(null)

function fetchList(p = page.value): void {
  store.fetchNotifications({
    page: p,
    size: size.value,
    isRead: readFilter.value === '' ? undefined : readFilter.value === 'true'
  })
}

async function markRead(n: NotificationVO): Promise<void> {
  markingId.value = n.id
  try {
    await store.markRead(n.id)
    ElMessage.success('已标记为已读')
  } catch (e) {
    ElMessage.error(friendlyMessage(e))
  } finally {
    markingId.value = null
  }
}

async function markAll(): Promise<void> {
  markingAll.value = true
  try {
    const updated = await store.markAllRead()
    // 刷新当前筛选视图（如“未读”筛选下全部被标读后应清空列表）
    fetchList(page.value)
    ElMessage.success(updated > 0 ? `已将 ${updated} 条通知标记为已读` : '没有未读通知')
  } catch (e) {
    ElMessage.error(friendlyMessage(e))
  } finally {
    markingAll.value = false
  }
}

function goMeeting(n: NotificationVO): void {
  if (n.meetingId) {
    router.push({ name: 'meeting-detail', params: { id: n.meetingId } })
  }
}

onMounted(() => fetchList(1))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>站内通知</h2>
      <div class="header-actions">
        <el-select
          v-model="readFilter"
          placeholder="全部"
          style="width: 120px"
          @change="fetchList(1)"
        >
          <el-option label="全部" value="" />
          <el-option label="未读" value="false" />
          <el-option label="已读" value="true" />
        </el-select>
        <el-button :loading="markingAll" @click="markAll">全部已读</el-button>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon class="state-block">
      <el-button size="small" @click="fetchList()">重试</el-button>
    </el-alert>

    <div v-else v-loading="loading" class="notification-list">
      <template v-if="records.length">
        <el-card
          v-for="n in records"
          :key="n.id"
          shadow="never"
          class="notification-item"
          :class="{ unread: !n.isRead }"
        >
          <div class="item-head">
            <el-badge is-dot :hidden="n.isRead">
              <el-tag size="small" :type="n.isRead ? 'info' : 'primary'">
                {{ NOTIFICATION_TYPE_TEXT[n.type] }}
              </el-tag>
            </el-badge>
            <span class="item-title">{{ n.title }}</span>
            <span class="item-time">{{ formatDateTime(n.createdAt) }}</span>
          </div>
          <p class="item-content">{{ n.content }}</p>
          <div class="item-actions">
            <el-button v-if="n.meetingId" link type="primary" size="small" @click="goMeeting(n)">
              查看会议
            </el-button>
            <el-button
              v-if="!n.isRead"
              link
              type="primary"
              size="small"
              :loading="markingId === n.id"
              @click="markRead(n)"
            >
              标记已读
            </el-button>
          </div>
        </el-card>
      </template>
      <EmptyState v-else-if="!loading" description="暂无通知" />
    </div>

    <div v-if="total > 0" class="pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="fetchList"
      />
    </div>
  </div>
</template>

<style scoped>
.page {
  padding: 20px;
  max-width: 860px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 12px;
}
.state-block {
  margin-bottom: 16px;
}
.notification-item {
  margin-bottom: 12px;
}
.notification-item.unread {
  border-left: 3px solid var(--el-color-primary);
}
.item-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.item-title {
  font-weight: 600;
}
.item-time {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.item-content {
  margin: 8px 0;
  color: var(--el-text-color-regular);
  font-size: 14px;
}
.item-actions {
  display: flex;
  justify-content: flex-end;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
