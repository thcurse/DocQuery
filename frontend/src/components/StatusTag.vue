<script setup lang="ts">
import { NTag } from 'naive-ui'
import { computed } from 'vue'
import {
  statusLabel,
  documentStatusLabel,
  versionStatusLabel,
  jobStatusLabel,
} from '../domain/display'
import type { StatusCode, JobStatus } from '../domain/types'
const props = defineProps<{ status: string; kind?: 'document' | 'version' | 'job' }>()
const label = computed(() =>
  props.kind === 'job'
    ? jobStatusLabel(props.status as JobStatus)
    : props.kind === 'version'
      ? versionStatusLabel(props.status as StatusCode)
      : props.kind === 'document'
        ? documentStatusLabel(props.status as StatusCode)
        : statusLabel(props.status as StatusCode),
)
const type = computed(() =>
  props.kind === 'job'
    ? ({ '1': 'info', '2': 'info', '3': 'success', '4': 'error' } as const)[
        props.status as JobStatus
      ]
    : props.kind === 'version'
      ? ({ '1': 'info', '2': 'success', '3': 'error' } as const)[props.status as StatusCode]
      : props.status === '1'
        ? 'success'
        : props.status === '2'
          ? 'warning'
          : 'default',
)
</script>
<template>
  <NTag :type="type" size="small" :bordered="false" round>{{ label }}</NTag>
</template>
