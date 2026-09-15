<script setup lang="ts">
// 参会者列表：展示全部参会记录（含已退出/被踢），有效口径 leftAt === null
import type { ParticipantVO } from '@/types/api'
import { LEAVE_REASON_TEXT } from '@/utils/format'
import { formatDateTime } from '@/utils/time'

interface Props {
  participants: ParticipantVO[]
  /** 是否可执行踢人（发起人或 ADMIN 且会议允许） */
  canKick?: boolean
  /** 当前登录用户 ID（避免对自己显示踢人按钮） */
  currentUserId?: number
}

const props = withDefaults(defineProps<Props>(), {
  canKick: false,
  currentUserId: undefined
})

const emit = defineEmits<{
  kick: [participant: ParticipantVO]
}>()

function statusTag(p: ParticipantVO): { text: string; type: 'success' | 'info' | 'danger' } {
  if (p.leftAt === null) return { text: '参会中', type: 'success' }
  if (p.leaveReason === 'KICKED') return { text: LEAVE_REASON_TEXT.KICKED, type: 'danger' }
  return { text: LEAVE_REASON_TEXT.USER_LEFT, type: 'info' }
}

function canKickRow(p: ParticipantVO): boolean {
  return props.canKick && p.leftAt === null && !p.isOrganizer && p.accountId !== props.currentUserId
}
</script>

<template>
  <el-table :data="props.participants" size="small" class="participant-table">
    <el-table-column label="用户名" min-width="120">
      <template #default="{ row }: { row: ParticipantVO }">
        <span>{{ row.username }}</span>
        <el-tag v-if="row.isOrganizer" size="small" type="warning" class="org-tag">发起人</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="100">
      <template #default="{ row }: { row: ParticipantVO }">
        <el-tag :type="statusTag(row).type" size="small">{{ statusTag(row).text }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="报名时间" min-width="150">
      <template #default="{ row }: { row: ParticipantVO }">
        {{ formatDateTime(row.joinedAt) }}
      </template>
    </el-table-column>
    <el-table-column label="退出时间" min-width="150">
      <template #default="{ row }: { row: ParticipantVO }">
        {{ row.leftAt ? formatDateTime(row.leftAt) : '-' }}
      </template>
    </el-table-column>
    <el-table-column v-if="props.canKick" label="操作" width="90" fixed="right">
      <template #default="{ row }: { row: ParticipantVO }">
        <el-button
          v-if="canKickRow(row)"
          type="danger"
          size="small"
          plain
          @click="emit('kick', row)"
        >
          移出
        </el-button>
      </template>
    </el-table-column>
    <template #empty>暂无参会者</template>
  </el-table>
</template>

<style scoped>
.org-tag {
  margin-left: 6px;
}
.participant-table {
  width: 100%;
}
</style>
