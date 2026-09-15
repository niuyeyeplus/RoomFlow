<script setup lang="ts">
// 会议创建表单：标题/说明/房间/起止时间
// 时间规则：开始限当前时间之后 72h 窗口内、起止均 15min 对齐、时长 15min-24h
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'
import { useRoomStore } from '@/stores/room'
import { useMeetingStore } from '@/stores/meeting'
import { ApiError } from '@/api/client'
import { friendlyMessage } from '@/utils/errors'
import {
  BOOKING_WINDOW_MS,
  MAX_DURATION_MS,
  MIN_DURATION_MS,
  isQuarterAligned,
  toBeijingIso
} from '@/utils/time'
import { EQUIPMENT_TEXT } from '@/utils/format'
import type { MeetingVO } from '@/types/api'

const emit = defineEmits<{
  success: [meeting: MeetingVO]
  cancel: []
}>()

const roomStore = useRoomStore()
const meetingStore = useMeetingStore()
const { rooms, loading: roomsLoading, error: roomsError } = storeToRefs(roomStore)

const formRef = ref<FormInstance>()
const submitting = ref(false)
const serverErrors = reactive<Record<string, string>>({})

const form = reactive<{
  title: string
  description: string
  roomId: number | null
  timeRange: [Date, Date] | null
}>({
  title: '',
  description: '',
  roomId: null,
  timeRange: null
})

const enabledRooms = computed(() => rooms.value.filter((r) => r.enabled))

/** 起止时间规则校验，返回错误文案或 null（供 EP rules 与提交门控共用） */
function checkTimeRange(value: [Date, Date] | null): string | null {
  if (!value || value.length !== 2 || !value[0] || !value[1]) {
    return '请选择会议起止时间'
  }
  const [start, end] = value
  // el-date-picker 可能携带非零秒/毫秒；就地归一化使校验与提交一致（契约要求秒=0）
  start.setSeconds(0, 0)
  end.setSeconds(0, 0)
  const now = Date.now()
  if (!isQuarterAligned(start) || !isQuarterAligned(end)) {
    return '起止时间须按15分钟对齐（分钟须为 00/15/30/45）'
  }
  if (start.getTime() < now) return '开始时间不能早于当前时间'
  if (start.getTime() > now + BOOKING_WINDOW_MS) return '开始时间须在当前时间之后72小时内'
  if (end.getTime() <= start.getTime()) return '结束时间须晚于开始时间'
  if (end.getTime() - start.getTime() < MIN_DURATION_MS) return '会议时长不能少于15分钟'
  if (end.getTime() - start.getTime() > MAX_DURATION_MS) return '会议时长不能超过24小时'
  return null
}

function validateTimeRange(
  _rule: unknown,
  value: [Date, Date] | null,
  callback: (error?: Error) => void
): void {
  const message = checkTimeRange(value)
  callback(message ? new Error(message) : undefined)
}

const rules: FormRules = {
  title: [
    { required: true, message: '请输入会议标题', trigger: 'blur' },
    { max: 200, message: '标题最长200字', trigger: 'blur' }
  ],
  description: [{ max: 5000, message: '说明最长5000字', trigger: 'blur' }],
  roomId: [{ required: true, message: '请选择会议室', trigger: 'change' }],
  timeRange: [{ required: true, validator: validateTimeRange, trigger: 'change' }]
}

function clearServerError(field: string): void {
  delete serverErrors[field]
}

/** 提交前的显式校验：错误写入 serverErrors，经 el-form-item :error 展示 */
function runLocalValidation(): boolean {
  Object.keys(serverErrors).forEach((k) => delete serverErrors[k])
  let ok = true
  if (!form.title.trim()) {
    serverErrors.title = '请输入会议标题'
    ok = false
  } else if (form.title.trim().length > 200) {
    serverErrors.title = '标题最长200字'
    ok = false
  }
  if (form.description.length > 5000) {
    serverErrors.description = '说明最长5000字'
    ok = false
  }
  if (form.roomId === null) {
    serverErrors.roomId = '请选择会议室'
    ok = false
  }
  const timeError = checkTimeRange(form.timeRange)
  if (timeError) {
    serverErrors.timeRange = timeError
    ok = false
  }
  return ok
}

async function submit(): Promise<void> {
  // 浏览器环境下同步触发 EP 校验渲染；提交门控以显式校验为准
  formRef.value?.validate().catch(() => false)
  if (!runLocalValidation() || !form.timeRange || form.roomId === null) return
  submitting.value = true
  try {
    const meeting = await meetingStore.createMeeting({
      title: form.title.trim(),
      description: form.description.trim() || null,
      roomId: form.roomId,
      startTime: toBeijingIso(form.timeRange[0]),
      endTime: toBeijingIso(form.timeRange[1])
    })
    ElMessage.success('会议创建成功')
    emit('success', meeting)
  } catch (e) {
    if (e instanceof ApiError && e.code === 40001 && e.fieldErrors.length > 0) {
      // 字段级错误映射回表单：startTime/endTime 归到 timeRange
      for (const fe of e.fieldErrors) {
        const field = fe.field === 'startTime' || fe.field === 'endTime' ? 'timeRange' : fe.field
        serverErrors[field] = fe.message
      }
    } else {
      ElMessage.error(friendlyMessage(e))
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 始终拉取启用房间：roomStore.rooms 可能被列表页按其他条件筛选过，
  // 复用缓存会导致“仅停用”筛选后下拉静默为空
  roomStore.fetchRooms(true)
})
</script>

<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent="submit">
    <el-form-item label="会议标题" prop="title" :error="serverErrors.title">
      <el-input
        v-model="form.title"
        maxlength="200"
        show-word-limit
        placeholder="请输入会议标题"
        @input="clearServerError('title')"
      />
    </el-form-item>
    <el-form-item label="会议说明" prop="description" :error="serverErrors.description">
      <el-input
        v-model="form.description"
        type="textarea"
        :rows="3"
        maxlength="5000"
        show-word-limit
        placeholder="选填，会议议题与说明"
        @input="clearServerError('description')"
      />
    </el-form-item>
    <el-alert
      v-if="roomsError"
      type="error"
      :title="`会议室列表加载失败：${roomsError}`"
      show-icon
      :closable="false"
      class="form-alert"
    >
      <el-button size="small" @click="roomStore.fetchRooms(true)">重试</el-button>
    </el-alert>
    <el-form-item label="会议室" prop="roomId" :error="serverErrors.roomId">
      <el-select
        v-model="form.roomId"
        placeholder="请选择会议室"
        :loading="roomsLoading"
        class="full-width"
        @change="clearServerError('roomId')"
      >
        <el-option
          v-for="room in enabledRooms"
          :key="room.id"
          :value="room.id"
          :label="`${room.name}（容量 ${room.capacity} 人）`"
        >
          <span>{{ room.name }}</span>
          <span class="room-option-meta">
            容量 {{ room.capacity }} 人
            <template v-if="room.equipment.length">
              · {{ room.equipment.map((e) => EQUIPMENT_TEXT[e]).join('/') }}
            </template>
          </span>
        </el-option>
      </el-select>
    </el-form-item>
    <el-form-item label="起止时间" prop="timeRange" :error="serverErrors.timeRange">
      <el-date-picker
        v-model="form.timeRange"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        format="YYYY-MM-DD HH:mm"
        class="full-width"
        @change="clearServerError('timeRange')"
      />
      <div class="time-hint">
        开始时间须在当前时间之后、72小时窗口内；起止时间按15分钟对齐；时长15分钟至24小时，允许跨天
      </div>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="submitting" native-type="submit">创建会议</el-button>
      <el-button :disabled="submitting" @click="emit('cancel')">取消</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.form-alert {
  margin-bottom: 12px;
}
.full-width {
  width: 100%;
}
.room-option-meta {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.time-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
