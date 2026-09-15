<script setup lang="ts">
// 会议创建/编辑表单：标题/说明/房间/起止时间
// 时间规则：开始限当前时间之后 72h 窗口内、起止均 15min 对齐、时长 15min-24h
// 编辑模式（传入 meeting）：预填原值；已开始的会议锁定房间/时间字段，
// 提交仍回传原 roomId/startTime/endTime（PUT 全量必填，不一致后端返回 40909）
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { useMeetingStore } from '@/stores/meeting'
import { listRooms } from '@/api/room'
import { ApiError } from '@/api/client'
import { friendlyMessage } from '@/utils/errors'
import { useNow } from '@/composables/useNow'
import {
  BOOKING_WINDOW_MS,
  MAX_DURATION_MS,
  MIN_DURATION_MS,
  isQuarterAligned,
  toBeijingIso
} from '@/utils/time'
import { EQUIPMENT_TEXT } from '@/utils/format'
import type { MeetingVO, RoomVO } from '@/types/api'

interface Props {
  /** 传入则为编辑模式；不传为创建模式 */
  meeting?: MeetingVO | null
}

const props = withDefaults(defineProps<Props>(), { meeting: null })

const emit = defineEmits<{
  success: [meeting: MeetingVO]
  cancel: []
}>()

const meetingStore = useMeetingStore()

// 表单自用房间列表：仅拉取启用房间且不写入 roomStore.rooms，
// 避免 enabled 筛选结果覆盖详情页用于容量判定的全量缓存
const roomList = ref<RoomVO[]>([])
const roomsLoading = ref(false)
const roomsError = ref<string | null>(null)

async function loadRooms(): Promise<void> {
  roomsLoading.value = true
  roomsError.value = null
  try {
    roomList.value = await listRooms(true)
  } catch (e) {
    roomList.value = []
    roomsError.value = friendlyMessage(e)
  } finally {
    roomsLoading.value = false
  }
}

const isEdit = computed(() => props.meeting !== null)

// 响应式时钟：驱动 timeLocked 随时间刷新——编辑弹窗跨过会议开始时刻后自动锁定
const now = useNow()

/** 已开始（startTime <= now）的会议：房间与时间锁定，仅可改标题/说明 */
const timeLocked = computed(
  () => !!props.meeting && new Date(props.meeting.startTime).getTime() <= now.value
)

/** 提交时以 Date.now() 现算锁定态，避免时钟刷新粒度在跨过开始边界瞬间误判 */
function isLockedNow(): boolean {
  return !!props.meeting && new Date(props.meeting.startTime).getTime() <= Date.now()
}

const formRef = ref<FormInstance>()
const submitting = ref(false)
const serverErrors = reactive<Record<string, string>>({})

const form = reactive<{
  title: string
  description: string
  roomId: number | null
  timeRange: [Date, Date] | null
}>({
  title: props.meeting?.title ?? '',
  description: props.meeting?.description ?? '',
  roomId: props.meeting?.roomId ?? null,
  timeRange: props.meeting
    ? [new Date(props.meeting.startTime), new Date(props.meeting.endTime)]
    : null
})

const enabledRooms = computed(() => roomList.value.filter((r) => r.enabled))

/** 编辑会议的原房间可能已停用而不在启用列表中，补一个占位选项保证回显 */
const roomOptions = computed<RoomVO[]>(() => {
  const list = enabledRooms.value
  const m = props.meeting
  if (m && !list.some((r) => r.id === m.roomId)) {
    return [
      ...list,
      {
        id: m.roomId,
        name: m.roomName,
        location: null,
        capacity: 0,
        equipment: [],
        enabled: false,
        createdAt: '',
        updatedAt: ''
      }
    ]
  }
  return list
})

/** 选项文案：含容量信息；已停用房间（仅编辑态占位出现）追加后缀提示 */
function roomLabel(room: RoomVO): string {
  const base = room.capacity > 0 ? `${room.name}（容量 ${room.capacity} 人）` : room.name
  return room.enabled ? base : `${base}（已停用）`
}

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
  // 已开始会议锁定时间字段且提交原值，不做新时间规则校验
  if (timeLocked.value) {
    callback()
    return
  }
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
  if (!isLockedNow()) {
    const timeError = checkTimeRange(form.timeRange)
    if (timeError) {
      serverErrors.timeRange = timeError
      ok = false
    }
  }
  return ok
}

async function submit(): Promise<void> {
  // 浏览器环境下同步触发 EP 校验渲染；提交门控以显式校验为准
  formRef.value?.validate().catch(() => false)
  if (!runLocalValidation() || !form.timeRange || form.roomId === null) return
  const m = props.meeting
  // PUT 全量语义：已开始会议回传原 roomId/startTime/endTime（不一致后端返回 40909）；
  // 锁定态以提交瞬间现算，避免时钟刷新粒度漏判“跨过开始时刻”的提交
  const locked = isLockedNow()
  const payload = {
    title: form.title.trim(),
    description: form.description.trim() || null,
    roomId: locked && m ? m.roomId : form.roomId,
    startTime: locked && m ? m.startTime : toBeijingIso(form.timeRange[0]),
    endTime: locked && m ? m.endTime : toBeijingIso(form.timeRange[1])
  }
  submitting.value = true
  try {
    const meeting =
      isEdit.value && m
        ? await meetingStore.updateMeeting(m.id, payload)
        : await meetingStore.createMeeting(payload)
    ElMessage.success(isEdit.value ? '会议已更新' : '会议创建成功')
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
  // 始终拉取启用房间：复用 roomStore 缓存可能拿到列表页其他筛选条件下的结果，
  // 且写入共享缓存会覆盖详情页的全量房间列表（容量判定依赖）
  loadRooms()
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
      <el-button size="small" @click="loadRooms">重试</el-button>
    </el-alert>
    <el-alert
      v-if="timeLocked"
      type="info"
      title="会议已开始，仅可修改会议标题与说明"
      show-icon
      :closable="false"
      class="form-alert"
    />
    <el-form-item label="会议室" prop="roomId" :error="serverErrors.roomId">
      <el-select
        v-model="form.roomId"
        placeholder="请选择会议室"
        :loading="roomsLoading"
        :disabled="timeLocked"
        class="full-width"
        @change="clearServerError('roomId')"
      >
        <el-option
          v-for="room in roomOptions"
          :key="room.id"
          :value="room.id"
          :label="roomLabel(room)"
        >
          <span>{{ room.name }}<template v-if="!room.enabled">（已停用）</template></span>
          <span v-if="room.capacity > 0" class="room-option-meta">
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
        :disabled="timeLocked"
        class="full-width"
        @change="clearServerError('timeRange')"
      />
      <div v-if="!timeLocked" class="time-hint">
        开始时间须在当前时间之后、72小时窗口内；起止时间按15分钟对齐；时长15分钟至24小时，允许跨天
      </div>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="submitting" native-type="submit">
        {{ isEdit ? '保存修改' : '创建会议' }}
      </el-button>
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
