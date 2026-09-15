<script setup lang="ts">
// 会议室列表 + 空闲时段查询（availability 返回占用时段，空闲区间前端计算）
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoomStore } from '@/stores/room'
import { useTimeSlot } from '@/composables/useTimeSlot'
import EmptyState from '@/components/EmptyState.vue'
import { EQUIPMENT_TEXT } from '@/utils/format'
import { beijingToday, formatTimeOnly } from '@/utils/time'
import type { RoomVO } from '@/types/api'

const roomStore = useRoomStore()
const { rooms, loading, error, availability, availabilityLoading, availabilityError } =
  storeToRefs(roomStore)
const { computeFreeSlots } = useTimeSlot()

type EnabledFilter = '' | 'true' | 'false'
const enabledFilter = ref<EnabledFilter>('')
const freeSlots = ref<{ start: string; end: string }[]>([])

const drawerVisible = ref(false)
const activeRoom = ref<RoomVO | null>(null)
const queryDate = ref<string>(beijingToday())

function fetchRooms(): void {
  const enabled = enabledFilter.value === '' ? undefined : enabledFilter.value === 'true'
  roomStore.fetchRooms(enabled)
}

watch(enabledFilter, fetchRooms)

async function openAvailability(room: RoomVO): Promise<void> {
  // 先清掉上一房间的查询结果，避免抽屉展开瞬间闪现旧数据
  roomStore.clearAvailability()
  freeSlots.value = []
  activeRoom.value = room
  queryDate.value = beijingToday()
  drawerVisible.value = true
  await queryAvailability()
}

async function queryAvailability(): Promise<void> {
  if (!activeRoom.value) return
  freeSlots.value = []
  await roomStore.fetchAvailability(activeRoom.value.id, queryDate.value)
  if (availability.value) {
    freeSlots.value = computeFreeSlots(queryDate.value, availability.value.occupiedSlots)
  }
}

onMounted(fetchRooms)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>会议室</h2>
      <el-select v-model="enabledFilter" placeholder="启用状态" style="width: 140px">
        <el-option label="全部" value="" />
        <el-option label="仅启用" value="true" />
        <el-option label="仅停用" value="false" />
      </el-select>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon class="state-block">
      <el-button size="small" @click="fetchRooms">重试</el-button>
    </el-alert>

    <el-table v-else v-loading="loading" :data="rooms">
      <el-table-column prop="name" label="会议室" min-width="140" />
      <el-table-column label="位置" min-width="120">
        <template #default="{ row }: { row: RoomVO }">
          {{ row.location || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="capacity" label="容量" width="80">
        <template #default="{ row }: { row: RoomVO }">{{ row.capacity }} 人</template>
      </el-table-column>
      <el-table-column label="设备" min-width="180">
        <template #default="{ row }: { row: RoomVO }">
          <template v-if="row.equipment.length">
            <el-tag v-for="e in row.equipment" :key="e" size="small" class="equip-tag">
              {{ EQUIPMENT_TEXT[e] }}
            </el-tag>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }: { row: RoomVO }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }: { row: RoomVO }">
          <el-button size="small" @click="openAvailability(row)">空闲查询</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <EmptyState description="暂无会议室" />
      </template>
    </el-table>

    <el-drawer
      v-model="drawerVisible"
      :title="activeRoom ? `${activeRoom.name} · 空闲时段` : '空闲时段'"
      size="420px"
    >
      <el-date-picker
        v-model="queryDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        :clearable="false"
        class="date-picker"
        @change="queryAvailability"
      />
      <div v-loading="availabilityLoading" class="availability-body">
        <el-alert v-if="availabilityError" type="error" :title="availabilityError" show-icon />
        <template v-else-if="availability">
          <h4>已占用时段</h4>
          <template v-if="availability.occupiedSlots.length">
            <div
              v-for="slot in availability.occupiedSlots"
              :key="slot.meetingId"
              class="slot occupied"
            >
              {{ formatTimeOnly(slot.startTime) }} - {{ formatTimeOnly(slot.endTime) }}
              <span class="slot-label">已占用</span>
            </div>
          </template>
          <p v-else class="slot-none">当日暂无占用</p>
          <h4>空闲时段</h4>
          <template v-if="freeSlots.length">
            <div v-for="slot in freeSlots" :key="slot.start" class="slot free">
              {{ formatTimeOnly(slot.start) }} - {{ formatTimeOnly(slot.end) }}
              <span class="slot-label">空闲</span>
            </div>
          </template>
          <p v-else class="slot-none">当日已约满</p>
        </template>
        <EmptyState v-else-if="!availabilityLoading" description="请选择日期查询" />
      </div>
    </el-drawer>
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
.equip-tag {
  margin-right: 4px;
}
.state-block {
  margin-bottom: 16px;
}
.date-picker {
  width: 100%;
  margin-bottom: 16px;
}
.availability-body h4 {
  margin: 16px 0 8px;
}
.slot {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 4px;
  margin-bottom: 6px;
  font-variant-numeric: tabular-nums;
}
.slot.occupied {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.slot.free {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.slot-label {
  font-size: 12px;
}
.slot-none {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
