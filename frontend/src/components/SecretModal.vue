<script setup lang="ts">
import { ref } from 'vue'
import { NModal, NCard, NAlert, NCheckbox, NButton } from 'naive-ui'
import { useAction } from '../composables/common'
defineProps<{ secret: string }>()
defineEmits<{ close: [] }>()
const saved = ref(false)
const { copy } = useAction()
</script>
<template>
  <NModal :show="true" :mask-closable="false" :close-on-esc="false"
    ><NCard title="凭证仅展示一次" class="form-modal" role="dialog" aria-modal="true"
      ><NAlert type="warning">关闭后无法再次查看，请保存到安全位置。</NAlert>
      <pre data-testid="created-secret">{{ secret }}</pre>
      <NButton @click="copy(secret)">复制凭证</NButton>
      <div class="field"><NCheckbox v-model:checked="saved">我已安全保存完整凭证</NCheckbox></div>
      <NButton type="primary" :disabled="!saved" @click="$emit('close')">完成</NButton></NCard
    ></NModal
  >
</template>
