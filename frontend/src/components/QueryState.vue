<script setup lang="ts">
import { NButton, NEmpty, NSkeleton, NAlert } from 'naive-ui'
import { errorMessage } from '../composables/common'
defineProps<{ loading?: boolean; error?: unknown; empty?: boolean }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="loading" class="state" role="status" aria-label="加载中">
    <NSkeleton text :repeat="4" />
  </div>
  <NAlert v-else-if="error" type="error" title="加载失败"
    ><p>{{ errorMessage(error) }}</p>
    <NButton @click="$emit('retry')">重试</NButton></NAlert
  ><NEmpty v-else-if="empty" class="state" description="暂无数据"
    ><template #extra><slot name="empty" /></template></NEmpty
  ><slot v-else />
</template>
