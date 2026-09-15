<script setup lang="ts">
// 会议详情：信息、分享链接复制、报名/退出、发起人踢人
// 本切片不含修改/取消/删除/提前结束 UI（属 PR-4）
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useMeetingStore } from '@/stores/meeting'
import { useAuthStore } from '@/stores/auth'
import ParticipantList from '@/components/ParticipantList.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import { friendlyMessage } from '@/utils/errors'
import { formatDateTime } from '@/utils/time'
import { MEETING_STATUS_TAG, MEETING_STATUS_TEXT } from '@/utils/format'
import type { ParticipantVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const meetingStore = useMeetingStore()
const authStore = useAuthStore()
const { detail, detailLoading, detailError, detailNotFound } = storeToRefs(meetingStore)
const { user, isAdmin } = storeToRefs(authStore)

const meetingId = computed(() => Number(route.params.id))
const actionLoading = ref(false)

const confirmState = ref<{
  visible: boolean
  kind: 'leave' | 'kick'
  target: ParticipantVO | null
}>({ visible: false, kind: 'leave', target: null })
const confirmLoading = ref(false)

const isOrganizer = computed(
  () => !!detail.value && !!user.value && detail.value.organizerId === user.value.id
)

const myParticipant = computed(() =>
  detail.value?.participants.find((p) => p.accountId === user.value?.id && p.leftAt === null)
)

// 响应式时钟：保证 hasStarted 等时间判定随时间推移自动刷新
const now = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined

const hasStarted = computed(
  () => !!detail.value && new Date(detail.value.startTime).getTime() <= now.value
)

const isActiveNotStarted = computed(
  () => !!detail.value && detail.value.status === 'ACTIVE' && !hasStarted.value
)

const canJoin = computed(
  () => isActiveNotStarted.value && !myParticipant.value && !isOrganizer.value
)
const canLeave = computed(
  () => isActiveNotStarted.value && !!myParticipant.value && !isOrganizer.value
)
const canKick = computed(
  () => !!detail.value && detail.value.status === 'ACTIVE' && (isOrganizer.value || isAdmin.value)
)

const shareLink = computed(() => `${window.location.origin}/meetings/${meetingId.value}`)

async function copyShareLink(): Promise<void> {
  try {
    await navigator.clipboard.writeText(shareLink.value)
    ElMessage.success('分享链接已复制')
  } catch {
    // 兼容无 clipboard 权限的环境
    const input = document.createElement('textarea')
    input.value = shareLink.value
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    document.body.removeChild(input)
    ElMessage.success('分享链接已复制')
  }
}

async function join(): Promise<void> {
  actionLoading.value = true
  try {
    await meetingStore.join(meetingId.value)
    ElMessage.success('报名成功')
  } catch (e) {
    ElMessage.error(friendlyMessage(e))
  } finally {
    actionLoading.value = false
  }
}

function askLeave(): void {
  confirmState.value = { visible: true, kind: 'leave', target: null }
}

function askKick(p: ParticipantVO): void {
  confirmState.value = { visible: true, kind: 'kick', target: p }
}

async function onConfirm(): Promise<void> {
  confirmLoading.value = true
  try {
    if (confirmState.value.kind === 'leave') {
      await meetingStore.leave(meetingId.value)
      ElMessage.success('已退出报名')
    } else if (confirmState.value.target) {
      await meetingStore.kick(meetingId.value, confirmState.value.target.accountId)
      ElMessage.success(`已将 ${confirmState.value.target.username} 移出会议`)
    }
    confirmState.value.visible = false
  } catch (e) {
    ElMessage.error(friendlyMessage(e))
  } finally {
    confirmLoading.value = false
  }
}

watch(
  () => route.params.id,
  () => {
    if (route.name === 'meeting-detail') meetingStore.fetchDetail(meetingId.value)
  }
)

onMounted(() => {
  authStore.ensureUser()
  meetingStore.fetchDetail(meetingId.value)
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})

onUnmounted(() => {
  if (clockTimer !== undefined) {
    clearInterval(clockTimer)
    clockTimer = undefined
  }
})
</script>

<template>
  <div class="page">
    <div v-loading="detailLoading" class="detail-wrap">
      <template v-if="detailNotFound">
        <EmptyState description="会议不存在或已删除">
          <el-button type="primary" @click="router.push({ name: 'meetings' })">
            返回会议列表
          </el-button>
        </EmptyState>
      </template>
      <template v-else-if="detailError && !detailLoading">
        <el-alert type="error" :title="detailError" show-icon>
          <el-button size="small" @click="meetingStore.fetchDetail(meetingId)">重试</el-button>
        </el-alert>
      </template>
      <template v-else-if="detail">
        <div class="page-header">
          <div class="title-row">
            <h2>{{ detail.title }}</h2>
            <el-tag :type="MEETING_STATUS_TAG[detail.status]">
              {{
                detail.status === 'ENDED' && detail.endedEarly
                  ? '已提前结束'
                  : MEETING_STATUS_TEXT[detail.status]
              }}
            </el-tag>
          </div>
          <!-- user 就绪前不渲染操作区，避免 ensureUser 竞态导致按钮闪烁/误显示 -->
          <div v-if="user" class="actions">
            <el-button @click="copyShareLink">复制分享链接</el-button>
            <el-button v-if="canJoin" type="primary" :loading="actionLoading" @click="join">
              报名参会
            </el-button>
            <el-button
              v-else-if="canLeave"
              type="danger"
              plain
              :loading="actionLoading"
              @click="askLeave"
            >
              退出报名
            </el-button>
          </div>
        </div>

        <el-descriptions :column="2" border class="desc">
          <el-descriptions-item label="会议室">{{ detail.roomName }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.organizerUsername }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">
            {{ formatDateTime(detail.startTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间">
            {{ formatDateTime(detail.endTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="参会人数">
            {{ detail.participantCount }} 人
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ formatDateTime(detail.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="会议说明" :span="2">
            {{ detail.description || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="section-title">参会者（{{ detail.participantCount }}）</h3>
        <ParticipantList
          :participants="detail.participants"
          :can-kick="canKick"
          :current-user-id="user?.id"
          @kick="askKick"
        />
      </template>
    </div>

    <ConfirmDialog
      v-model="confirmState.visible"
      :type="confirmState.kind === 'kick' ? 'danger' : 'warning'"
      :title="confirmState.kind === 'kick' ? '移出参会者' : '退出报名'"
      :message="
        confirmState.kind === 'kick'
          ? `确定将 ${confirmState.target?.username ?? ''} 移出会议吗？移出后该用户将无法再次报名。`
          : '确定退出报名吗？'
      "
      :confirm-text="confirmState.kind === 'kick' ? '移出' : '退出报名'"
      :loading="confirmLoading"
      @confirm="onConfirm"
    />
  </div>
</template>

<style scoped>
.page {
  padding: 20px;
}
.detail-wrap {
  max-width: 960px;
  min-height: 200px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.title-row h2 {
  margin: 0;
}
.desc {
  margin-bottom: 20px;
}
.section-title {
  margin: 0 0 12px;
}
</style>
