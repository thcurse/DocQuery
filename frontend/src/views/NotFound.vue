<script setup lang="ts">
import { NResult, NButton } from 'naive-ui'
defineProps<{ forbidden?: boolean }>()
</script>
<template>
  <NResult
    :status="forbidden ? '403' : '404'"
    :title="forbidden ? '无权访问' : '页面不存在'"
    class="state"
    ><template #footer
      ><RouterLink to="/"><NButton>返回工作台</NButton></RouterLink></template
    ></NResult
  >
</template>
