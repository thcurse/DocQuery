<script setup lang="ts">
import { NModal, NCard, NButton } from 'naive-ui'
defineProps<{ title: string; busy?: boolean; submitText?: string }>()
defineEmits<{ close: []; submit: [] }>()
</script>
<template>
  <NModal
    :show="true"
    :mask-closable="!busy"
    :close-on-esc="!busy"
    @update:show="!busy && $emit('close')"
    ><NCard class="form-modal" :title="title" role="dialog" aria-modal="true" :aria-label="title"
      ><form @submit.prevent="$emit('submit')">
        <slot />
        <footer class="modal-actions">
          <NButton :disabled="busy" @click="$emit('close')">取消</NButton
          ><NButton type="primary" attr-type="submit" :loading="busy">{{
            submitText || '保存'
          }}</NButton>
        </footer>
      </form></NCard
    ></NModal
  >
</template>
