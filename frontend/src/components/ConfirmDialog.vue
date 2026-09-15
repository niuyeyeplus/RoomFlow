<script setup lang="ts">
// 通用确认对话框：退出报名、踢人等破坏性操作必须经此确认
interface Props {
  modelValue: boolean
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  type?: 'warning' | 'danger' | 'info'
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '操作确认',
  confirmText: '确认',
  cancelText: '取消',
  type: 'warning',
  loading: false
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
  cancel: []
}>()

function close(): void {
  emit('update:modelValue', false)
  emit('cancel')
}

function handleConfirm(): void {
  emit('confirm')
}
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="props.title"
    width="420px"
    :close-on-click-modal="!props.loading"
    :close-on-press-escape="!props.loading"
    :show-close="!props.loading"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="confirm-body">
      <el-icon v-if="props.type === 'danger'" class="confirm-icon danger"
        ><WarningFilled
      /></el-icon>
      <el-icon v-else class="confirm-icon warning"><WarningFilled /></el-icon>
      <span>{{ props.message }}</span>
    </div>
    <template #footer>
      <el-button :disabled="props.loading" @click="close">{{ props.cancelText }}</el-button>
      <el-button
        :type="props.type === 'danger' ? 'danger' : 'primary'"
        :loading="props.loading"
        @click="handleConfirm"
      >
        {{ props.confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.confirm-body {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}
.confirm-icon {
  font-size: 22px;
}
.confirm-icon.warning {
  color: var(--el-color-warning);
}
.confirm-icon.danger {
  color: var(--el-color-danger);
}
</style>
