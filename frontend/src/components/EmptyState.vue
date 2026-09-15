<script setup lang="ts">
// 空状态占位
interface Props {
  description?: string
  imageSize?: number
}

withDefaults(defineProps<Props>(), {
  description: '暂无数据',
  imageSize: 120
})
</script>

<template>
  <el-empty :description="description" :image-size="imageSize">
    <slot />
  </el-empty>
</template>
