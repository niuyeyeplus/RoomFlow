<script setup lang="ts">
// 管理后台·会议室管理（仅 ADMIN）
// 拦截双保险：路由 meta.requiresAdmin + 后端 @PreAuthorize；普通用户无入口且直接访问被重定向
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useRoomStore } from '@/stores/room'
import { friendlyMessage } from '@/utils/errors'
import { EQUIPMENT_TEXT } from '@/utils/format'
import RoomForm from '@/components/RoomForm.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { RoomVO } from '@/types/api'

const roomStore = useRoomStore()
const { rooms, loading, error } = storeToRefs(roomStore)

type EnabledFilter = '' | 'true' | 'false'
const enabledFilter = ref<EnabledFilter>('')

const formVisible = ref(false)
const editingRoom = ref<RoomVO | null>(null)

type ConfirmKind = 'disable' | 'delete'
const confirmVisible = ref(false)
const confirmKind = ref<ConfirmKind>('disable')
const confirmTarget = ref<RoomVO | null>(null)
const confirmLoading = ref(false)

function fetchRooms(): void {
  const enabled = enabledFilter.value === '' ? undefined : enabledFilter.value === 'true'
  roomStore.fetchRooms(enabled)
}

watch(enabledFilter, fetchRooms)
onMounted(fetchRooms)

function openCreate(): void {
  editingRoom.value = null
  formVisible.value = true
}

function openEdit(room: RoomVO): void {
  editingRoom.value = room
  formVisible.value = true
}

function onFormSuccess(): void {
  formVisible.value = false
  editingRoom.value = null
}

/** 启停切换：停用为破坏性操作需确认；启用直接执行 */
async function toggleEnabled(room: RoomVO): Promise<void> {
  if (room.enabled) {
    confirmKind.value = 'disable'
    confirmTarget.value = room
    confirmVisible.value = true
    return
  }
  try {
    await roomStore.setRoomEnabled(room.id, true)
    ElMessage.success(`已启用「${room.name}」`)
  } catch (e) {
    ElMessage.error(friendlyMessage(e))
  }
}

/** 删除为软删除（置为停用）：仅对启用中的房间展示入口 */
function requestDelete(room: RoomVO): void {
  confirmKind.value = 'delete'
  confirmTarget.value = room
  confirmVisible.value = true
}

async function handleConfirm(): Promise<void> {
  const room = confirmTarget.value
  if (!room) return
  confirmLoading.value = true
  try {
    if (confirmKind.value === 'disable') {
      await roomStore.setRoomEnabled(room.id, false)
      ElMessage.success(`已停用「${room.name}」`)
    } else {
      await roomStore.removeRoom(room.id)
      ElMessage.success(`已删除「${room.name}」`)
    }
    confirmVisible.value = false
    confirmTarget.value = null
  } catch (e) {
    // 40907：存在未结束的 ACTIVE 会议，拒绝停用/删除
    ElMessage.error(friendlyMessage(e))
  } finally {
    confirmLoading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2>会议室管理</h2>
      <div class="header-actions">
        <el-select v-model="enabledFilter" placeholder="启用状态" style="width: 140px">
          <el-option label="全部" value="" />
          <el-option label="仅启用" value="true" />
          <el-option label="仅停用" value="false" />
        </el-select>
        <el-button type="primary" @click="openCreate">新建会议室</el-button>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon class="state-block">
      <el-button size="small" @click="fetchRooms">重试</el-button>
    </el-alert>

    <el-table v-else v-loading="loading" :data="rooms">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="会议室" min-width="140" />
      <el-table-column label="位置" min-width="120">
        <template #default="{ row }: { row: RoomVO }">
          {{ row.location || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="capacity" label="容量" width="80">
        <template #default="{ row }: { row: RoomVO }">{{ row.capacity }} 人</template>
      </el-table-column>
      <el-table-column label="设备" min-width="160">
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
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }: { row: RoomVO }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            size="small"
            :type="row.enabled ? 'warning' : 'success'"
            @click="toggleEnabled(row)"
          >
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button v-if="row.enabled" size="small" type="danger" @click="requestDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <EmptyState description="暂无会议室" />
      </template>
    </el-table>

    <el-dialog
      v-model="formVisible"
      :title="editingRoom ? '编辑会议室' : '新建会议室'"
      width="520px"
      destroy-on-close
    >
      <RoomForm :room="editingRoom" @success="onFormSuccess" @cancel="formVisible = false" />
    </el-dialog>

    <ConfirmDialog
      v-model="confirmVisible"
      :title="confirmKind === 'delete' ? '删除会议室' : '停用会议室'"
      :message="
        confirmKind === 'delete'
          ? `删除「${confirmTarget?.name ?? ''}」为软删除（置为停用），历史会议关联保留；存在未结束会议时将被拒绝。确定删除吗？`
          : `停用「${confirmTarget?.name ?? ''}」后不可再被预约；存在未结束会议时将被拒绝。确定停用吗？`
      "
      :confirm-text="confirmKind === 'delete' ? '删除' : '停用'"
      :type="confirmKind === 'delete' ? 'danger' : 'warning'"
      :loading="confirmLoading"
      @confirm="handleConfirm"
    />
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
.header-actions {
  display: flex;
  gap: 12px;
}
.equip-tag {
  margin-right: 4px;
}
.state-block {
  margin-bottom: 16px;
}
</style>
