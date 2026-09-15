<script setup lang="ts">
// 会议列表：分页 + 房间/日期/状态筛选 + 创建会议
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useMeetingStore } from '@/stores/meeting'
import { useRoomStore } from '@/stores/room'
import MeetingForm from '@/components/MeetingForm.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useNow } from '@/composables/useNow'
import { meetingStatusTag } from '@/utils/format'
import { formatDateTime } from '@/utils/time'
import type { ListMeetingsParams, MeetingStatusFilter, MeetingVO } from '@/types/api'

const router = useRouter()
const meetingStore = useMeetingStore()
const roomStore = useRoomStore()
const { meetings, total, page, size, loading, error } = storeToRefs(meetingStore)
const { rooms } = storeToRefs(roomStore)

// 响应式时钟：状态徽章（未开始/进行中）越过开始时刻后自动刷新
const now = useNow()

const filters = reactive<{ roomId: number | null; date: string; status: MeetingStatusFilter | '' }>(
  {
    roomId: null,
    date: '',
    status: ''
  }
)

const createVisible = ref(false)

function buildParams(p = page.value): ListMeetingsParams {
  return {
    page: p,
    size: size.value,
    roomId: filters.roomId ?? undefined,
    date: filters.date || undefined,
    status: filters.status || undefined
  }
}

function search(): void {
  meetingStore.fetchMeetings(buildParams(1))
}

function resetFilters(): void {
  filters.roomId = null
  filters.date = ''
  filters.status = ''
  search()
}

function changePage(p: number): void {
  meetingStore.fetchMeetings(buildParams(p))
}

function openDetail(row: MeetingVO): void {
  router.push({ name: 'meeting-detail', params: { id: row.id } })
}

function onCreated(meeting: MeetingVO): void {
  createVisible.value = false
  search()
  router.push({ name: 'meeting-detail', params: { id: meeting.id } })
}

onMounted(() => {
  search()
  roomStore.fetchRooms()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>会议列表</h2>
      <el-button type="primary" @click="createVisible = true">创建会议</el-button>
    </div>

    <div class="filters">
      <el-select
        v-model="filters.roomId"
        placeholder="会议室"
        clearable
        style="width: 160px"
        @change="search"
      >
        <el-option v-for="r in rooms" :key="r.id" :label="r.name" :value="r.id" />
      </el-select>
      <el-date-picker
        v-model="filters.date"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="日期"
        style="width: 160px"
        @change="search"
      />
      <el-select
        v-model="filters.status"
        placeholder="状态"
        clearable
        style="width: 130px"
        @change="search"
      >
        <el-option label="有效" value="ACTIVE" />
        <el-option label="已结束" value="ENDED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon class="state-block">
      <el-button size="small" @click="search">重试</el-button>
    </el-alert>

    <el-table v-else v-loading="loading" :data="meetings" @row-click="openDetail">
      <el-table-column prop="title" label="会议标题" min-width="180">
        <template #default="{ row }: { row: MeetingVO }">
          <el-link type="primary" :underline="false">{{ row.title }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="roomName" label="会议室" min-width="120" />
      <el-table-column label="时间" min-width="230">
        <template #default="{ row }: { row: MeetingVO }">
          {{ formatDateTime(row.startTime) }} ~ {{ formatDateTime(row.endTime) }}
        </template>
      </el-table-column>
      <el-table-column prop="organizerUsername" label="发起人" width="110" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }: { row: MeetingVO }">
          <el-tag :type="meetingStatusTag(row, now).type" size="small">
            {{ meetingStatusTag(row, now).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="participantCount" label="参会人数" width="90" align="center" />
      <template #empty>
        <EmptyState description="暂无会议" />
      </template>
    </el-table>

    <div v-if="total > 0" class="pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="changePage"
      />
    </div>

    <el-dialog v-model="createVisible" title="创建会议" width="520px" destroy-on-close>
      <MeetingForm @success="onCreated" @cancel="createVisible = false" />
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 20px;
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
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.state-block {
  margin-bottom: 16px;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
:deep(.el-table__row) {
  cursor: pointer;
}
</style>
