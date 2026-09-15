<script setup lang="ts">
// 会议室创建/编辑表单（ADMIN）：room 为 null 时创建，否则编辑
// 启停不走本表单（enabled 由 PATCH /api/rooms/{id}/status 单独管理）
import { reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { useRoomStore } from '@/stores/room'
import { ApiError } from '@/api/client'
import { friendlyMessage } from '@/utils/errors'
import { EQUIPMENT_TEXT } from '@/utils/format'
import type { CreateRoomRequest, Equipment, RoomVO } from '@/types/api'

const props = defineProps<{ room: RoomVO | null }>()
const emit = defineEmits<{
  success: [room: RoomVO]
  cancel: []
}>()

const roomStore = useRoomStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const serverErrors = reactive<Record<string, string>>({})

const EQUIPMENT_OPTIONS = Object.keys(EQUIPMENT_TEXT) as Equipment[]

const form = reactive<{
  name: string
  location: string
  capacity: number | null
  equipment: Equipment[]
}>({ name: '', location: '', capacity: null, equipment: [] })

// 切换编辑对象或进入创建模式时重置表单与错误（配合外层 destroy-on-close 双保险）
watch(
  () => props.room,
  (room) => {
    form.name = room?.name ?? ''
    form.location = room?.location ?? ''
    form.capacity = room?.capacity ?? null
    form.equipment = room ? [...room.equipment] : []
    Object.keys(serverErrors).forEach((k) => delete serverErrors[k])
    formRef.value?.clearValidate()
  },
  { immediate: true }
)

const rules: FormRules = {
  name: [
    { required: true, message: '请输入会议室名称', trigger: 'blur' },
    { max: 100, message: '名称最长100字', trigger: 'blur' }
  ],
  location: [{ max: 200, message: '位置最长200字', trigger: 'blur' }],
  capacity: [{ required: true, message: '请设置容量', trigger: 'change' }]
}

function clearServerError(field: string): void {
  delete serverErrors[field]
}

/** 提交前的显式校验：错误写入 serverErrors，经 el-form-item :error 展示 */
function runLocalValidation(): boolean {
  Object.keys(serverErrors).forEach((k) => delete serverErrors[k])
  let ok = true
  const name = form.name.trim()
  if (!name) {
    serverErrors.name = '请输入会议室名称'
    ok = false
  } else if (name.length > 100) {
    serverErrors.name = '名称最长100字'
    ok = false
  }
  if (form.location.length > 200) {
    serverErrors.location = '位置最长200字'
    ok = false
  }
  if (form.capacity === null) {
    serverErrors.capacity = '请设置容量'
    ok = false
  } else if (!Number.isInteger(form.capacity) || form.capacity < 1 || form.capacity > 100) {
    serverErrors.capacity = '容量须为1-100的整数'
    ok = false
  }
  return ok
}

async function submit(): Promise<void> {
  // 浏览器环境下同步触发 EP 校验渲染；提交门控以显式校验为准
  formRef.value?.validate().catch(() => false)
  if (!runLocalValidation() || form.capacity === null) return
  submitting.value = true
  try {
    const payload: CreateRoomRequest = {
      name: form.name.trim(),
      location: form.location.trim() || null,
      capacity: form.capacity,
      equipment: [...form.equipment]
    }
    const saved = props.room
      ? await roomStore.updateRoom(props.room.id, payload)
      : await roomStore.createRoom(payload)
    ElMessage.success(props.room ? '会议室已更新' : '会议室创建成功')
    emit('success', saved)
  } catch (e) {
    if (e instanceof ApiError && e.code === 40001 && e.fieldErrors.length > 0) {
      // 字段级错误映射回表单（字段名与表单 prop 一致）
      for (const fe of e.fieldErrors) {
        serverErrors[fe.field] = fe.message
      }
    } else {
      ElMessage.error(friendlyMessage(e))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent="submit">
    <el-form-item label="名称" prop="name" :error="serverErrors.name">
      <el-input
        v-model="form.name"
        maxlength="100"
        show-word-limit
        placeholder="请输入会议室名称"
        @input="clearServerError('name')"
      />
    </el-form-item>
    <el-form-item label="位置" prop="location" :error="serverErrors.location">
      <el-input
        v-model="form.location"
        maxlength="200"
        show-word-limit
        placeholder="选填，如 3楼东侧"
        @input="clearServerError('location')"
      />
    </el-form-item>
    <el-form-item label="容量" prop="capacity" :error="serverErrors.capacity">
      <el-input-number
        v-model="form.capacity"
        :min="1"
        :max="100"
        :step="1"
        step-strictly
        placeholder="1-100 人"
        @change="clearServerError('capacity')"
      />
      <span class="capacity-hint">1-100 人</span>
    </el-form-item>
    <el-form-item label="设备" prop="equipment" :error="serverErrors.equipment">
      <el-checkbox-group v-model="form.equipment">
        <el-checkbox v-for="opt in EQUIPMENT_OPTIONS" :key="opt" :value="opt">
          {{ EQUIPMENT_TEXT[opt] }}
        </el-checkbox>
      </el-checkbox-group>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="submitting" native-type="submit">
        {{ props.room ? '保存修改' : '创建会议室' }}
      </el-button>
      <el-button :disabled="submitting" @click="emit('cancel')">取消</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.capacity-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
