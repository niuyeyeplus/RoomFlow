<script setup lang="ts">
// 我的会议：onlyMine=true（我发起的 + 我正在参与的）
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useMeetingStore } from '@/stores/meeting'
import EmptyState from '@/components/EmptyState.vue'
import { useNow } from '@/composables/useNow'
import { meetingStatusTag } from '@/utils/format'
import { formatDateTime } from '@/utils/time'
import type { MeetingStatusFilter, MeetingVO } from '@/types/api'

const router = useRouter()
const meetingStore = useMeetingStore()
const { meetings, total, page, size, loading, error } = storeToRefs(meetingStore)

// 响应式时钟：状态徽章（未开始/进行中）越过开始时刻后自动刷新
const now = useNow()

const status = ref<MeetingStatusFilter | ''>('')

function search(p = page.value): void {
  meetingStore.fetchMeetings({
    page: p,
    size: size.value,
    onlyMine: true,
    status: status.value || undefined
  })
}

function openDetail(row: MeetingVO): void {
  router.push({ name: 'meeting-detail', params: { id: row.id } })
}

onMounted(() => search(1))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>我的会议</h2>
      <el-select
        v-model="status"
        placeholder="状态"
        clearable
        style="width: 130px"
        @change="search(1)"
      >
        <el-option label="有效" value="ACTIVE" />
        <el-option label="已结束" value="ENDED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon class="state-block">
      <el-button size="small" @click="search()">重试</el-button>
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
        <EmptyState description="暂无相关会议" />
      </template>
    </el-table>

    <div v-if="total > 0" class="pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="search"
      />
    </div>
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
