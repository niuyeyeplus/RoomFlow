<script setup lang="ts">
// 会议详情：信息、分享链接复制、报名/退出、生命周期管理（编辑/取消/提前结束/删除）、踢人
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useMeetingStore } from '@/stores/meeting'
import { useAuthStore } from '@/stores/auth'
import { useRoomStore } from '@/stores/room'
import ParticipantList from '@/components/ParticipantList.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import MeetingForm from '@/components/MeetingForm.vue'
import EmptyState from '@/components/EmptyState.vue'
import { friendlyMessage } from '@/utils/errors'
import { formatDateTime } from '@/utils/time'
import { useNow } from '@/composables/useNow'
import { meetingStatusTag } from '@/utils/format'
import type { ParticipantVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const meetingStore = useMeetingStore()
const authStore = useAuthStore()
const roomStore = useRoomStore()
const { detail, detailLoading, detailError, detailNotFound } = storeToRefs(meetingStore)
const { user, isAdmin } = storeToRefs(authStore)
const { rooms } = storeToRefs(roomStore)

const meetingId = computed(() => Number(route.params.id))
const actionLoading = ref(false)
const editVisible = ref(false)

type ConfirmKind = 'leave' | 'kick' | 'cancel' | 'endEarly' | 'delete'
const confirmState = ref<{
  visible: boolean
  kind: ConfirmKind
  target: ParticipantVO | null
}>({ visible: false, kind: 'leave', target: null })
const confirmLoading = ref(false)

const isOrganizer = computed(
  () => !!detail.value && !!user.value && detail.value.organizerId === user.value.id
)

/** 管理入口（编辑/取消/提前结束/删除/踢人）仅发起人或 ADMIN 可见；后端另有 403 拦截 */
const canManage = computed(() => isOrganizer.value || isAdmin.value)

const myParticipant = computed(() =>
  detail.value?.participants.find((p) => p.accountId === user.value?.id && p.leftAt === null)
)

const isBanned = computed(() => {
  const uid = user.value?.id
  return (
    uid !== undefined &&
    !!detail.value &&
    detail.value.participants.some((p) => p.accountId === uid && p.banned)
  )
})

// 响应式时钟：保证 hasStarted 等时间判定随时间推移自动刷新
const now = useNow()

// 房间列表是否已返回（成功或失败）：容量就绪前报名按钮保持加载态，避免“已满”判定空窗期误点
const roomsResolved = ref(false)

const hasStarted = computed(
  () => !!detail.value && new Date(detail.value.startTime).getTime() <= now.value
)

const isActiveNotStarted = computed(
  () => !!detail.value && detail.value.status === 'ACTIVE' && !hasStarted.value
)

/** 进行中：status=ACTIVE 且 startTime <= now < endTime（契约 end-early 前置条件） */
const isInProgress = computed(
  () =>
    !!detail.value &&
    detail.value.status === 'ACTIVE' &&
    hasStarted.value &&
    now.value < new Date(detail.value.endTime).getTime()
)

const statusTag = computed(() => (detail.value ? meetingStatusTag(detail.value, now.value) : null))

/** 会议室容量（用于“报名已满”提示；房间列表加载失败时退化为 null，由后端兜底 40903） */
const roomCapacity = computed<number | null>(() => {
  const roomId = detail.value?.roomId
  if (roomId === undefined) return null
  return rooms.value.find((r) => r.id === roomId)?.capacity ?? null
})

const isFull = computed(
  () =>
    !!detail.value &&
    roomCapacity.value !== null &&
    detail.value.participantCount >= roomCapacity.value
)

const canJoin = computed(
  () => isActiveNotStarted.value && !myParticipant.value && !isOrganizer.value && !isFull.value
)
const canLeave = computed(
  () => isActiveNotStarted.value && !!myParticipant.value && !isOrganizer.value
)
const canKick = computed(
  () => !!detail.value && detail.value.status === 'ACTIVE' && canManage.value
)

/** 不可报名原因（普通用户视角）：已取消/已结束/已被移出/已开始截止/已满 */
const joinBlockReason = computed<string | null>(() => {
  const d = detail.value
  if (!d || !user.value || isOrganizer.value || myParticipant.value) return null
  if (d.status === 'CANCELLED') return '会议已取消，无法报名'
  if (d.status === 'ENDED')
    return d.endedEarly ? '会议已提前结束，无法报名' : '会议已结束，无法报名'
  if (isBanned.value) return '你已被移出该会议，无法再次报名'
  if (hasStarted.value) return '会议已开始，报名已截止'
  if (isFull.value) return '报名人数已满'
  return null
})

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

function askCancel(): void {
  confirmState.value = { visible: true, kind: 'cancel', target: null }
}

function askEndEarly(): void {
  confirmState.value = { visible: true, kind: 'endEarly', target: null }
}

function askDelete(): void {
  confirmState.value = { visible: true, kind: 'delete', target: null }
}

const confirmMeta = computed<{
  title: string
  message: string
  confirmText: string
  type: 'warning' | 'danger'
}>(() => {
  switch (confirmState.value.kind) {
    case 'kick':
      return {
        title: '移出参会者',
        message: `确定将 ${confirmState.value.target?.username ?? ''} 移出会议吗？移出后该用户将无法再次报名。`,
        confirmText: '移出',
        type: 'danger'
      }
    case 'cancel':
      return {
        title: '取消会议',
        message: `确定取消会议「${detail.value?.title ?? ''}」吗？取消后占用时段将被释放，会议标记为已取消。`,
        confirmText: '取消会议',
        type: 'warning'
      }
    case 'endEarly':
      return {
        title: '提前结束会议',
        message: `确定提前结束会议「${detail.value?.title ?? ''}」吗？剩余时段将立即释放。`,
        confirmText: '提前结束',
        type: 'warning'
      }
    case 'delete':
      return {
        title: '删除会议',
        message: `确定删除会议「${detail.value?.title ?? ''}」吗？删除后会议不再可见且不可恢复。`,
        confirmText: '删除',
        type: 'danger'
      }
    default:
      return {
        title: '退出报名',
        message: '确定退出报名吗？',
        confirmText: '退出报名',
        type: 'warning'
      }
  }
})

async function onConfirm(): Promise<void> {
  confirmLoading.value = true
  try {
    const kind = confirmState.value.kind
    if (kind === 'leave') {
      await meetingStore.leave(meetingId.value)
      ElMessage.success('已退出报名')
    } else if (kind === 'kick' && confirmState.value.target) {
      await meetingStore.kick(meetingId.value, confirmState.value.target.accountId)
      ElMessage.success(`已将 ${confirmState.value.target.username} 移出会议`)
    } else if (kind === 'cancel') {
      await meetingStore.cancelMeeting(meetingId.value)
      ElMessage.success('会议已取消')
    } else if (kind === 'endEarly') {
      await meetingStore.endMeetingEarly(meetingId.value)
      ElMessage.success('会议已提前结束')
    } else if (kind === 'delete') {
      await meetingStore.removeMeeting(meetingId.value)
      ElMessage.success('会议已删除')
      confirmState.value.visible = false
      router.push({ name: 'meetings' })
      return
    }
    confirmState.value.visible = false
  } catch (e) {
    ElMessage.error(friendlyMessage(e))
  } finally {
    confirmLoading.value = false
  }
}

function onUpdated(): void {
  // store 已同步 detail 缓存，这里只需关闭弹窗
  editVisible.value = false
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
  // 拉取全量房间列表用于“报名已满”判定；失败时退化为不显示该提示，由后端 40903 兜底。
  // 编辑弹窗的 MeetingForm 不写入 roomStore.rooms，本缓存不受其 enabled 筛选污染
  roomStore.fetchRooms().finally(() => {
    roomsResolved.value = true
  })
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
            <el-tag v-if="statusTag" :type="statusTag.type">{{ statusTag.text }}</el-tag>
          </div>
          <!-- user 就绪前不渲染操作区，避免 ensureUser 竞态导致按钮闪烁/误显示 -->
          <div v-if="user" class="actions">
            <el-button @click="copyShareLink">复制分享链接</el-button>
            <el-button
              v-if="canJoin"
              type="primary"
              :loading="actionLoading || !roomsResolved"
              @click="join"
            >
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
            <template v-if="canManage">
              <el-button v-if="detail.status === 'ACTIVE'" @click="editVisible = true">
                编辑会议
              </el-button>
              <el-button v-if="isActiveNotStarted" type="warning" plain @click="askCancel">
                取消会议
              </el-button>
              <el-button v-if="isInProgress" type="warning" plain @click="askEndEarly">
                提前结束
              </el-button>
              <el-button type="danger" plain @click="askDelete">删除会议</el-button>
            </template>
          </div>
        </div>

        <el-alert
          v-if="joinBlockReason"
          type="info"
          :title="joinBlockReason"
          show-icon
          :closable="false"
          class="block-alert"
        />

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

    <el-dialog v-if="detail" v-model="editVisible" title="编辑会议" width="520px" destroy-on-close>
      <MeetingForm :meeting="detail" @success="onUpdated" @cancel="editVisible = false" />
    </el-dialog>

    <ConfirmDialog
      v-model="confirmState.visible"
      :type="confirmMeta.type"
      :title="confirmMeta.title"
      :message="confirmMeta.message"
      :confirm-text="confirmMeta.confirmText"
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
.block-alert {
  margin-bottom: 16px;
}
.desc {
  margin-bottom: 20px;
}
.section-title {
  margin: 0 0 12px;
}
</style>
