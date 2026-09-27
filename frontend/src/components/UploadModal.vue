<script setup lang="ts">
import { ref } from 'vue'
import { NAlert } from 'naive-ui'
import FormModal from './FormModal.vue'
import { api } from '../api/client'
import { useAction } from '../composables/common'
const props = defineProps<{ tenantId: number; knowledgeBaseId: number; documentId?: number }>()
const emit = defineEmits<{ close: []; uploaded: [] }>()
const name = ref('')
const file = ref<File>()
const { busy, run } = useAction()
function select(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0]
}
function upload() {
  void run(
    async () => {
      if (!file.value) throw new Error('请选择文件')
      if (file.value.size > 50 * 1024 * 1024) throw new Error('文件不能超过 50 MiB')
      if (!/\.(pdf|docx|txt|md|markdown)$/i.test(file.value.name))
        throw new Error('不支持此文件格式')
      if (props.documentId)
        await api.uploadDocumentVersion(
          props.tenantId,
          props.knowledgeBaseId,
          props.documentId,
          file.value,
          crypto.randomUUID(),
        )
      else {
        if (!name.value.trim()) throw new Error('请输入文档名称')
        await api.uploadDocument(
          props.tenantId,
          props.knowledgeBaseId,
          name.value.trim(),
          file.value,
          crypto.randomUUID(),
        )
      }
    },
    '上传已受理',
    () => {
      file.value = undefined
      emit('uploaded')
      emit('close')
    },
  )
}
</script>
<template>
  <FormModal
    :title="documentId ? '上传新版本' : '上传新文档'"
    submit-text="开始上传"
    :busy="busy"
    @close="$emit('close')"
    @submit="upload"
    ><NAlert v-if="documentId" type="info">新版本就绪后自动切换；处理期间旧版本继续可用。</NAlert
    ><label v-else class="field"
      >文档名称<input
        v-model="name"
        aria-label="文档名称"
        required
        maxlength="200"
        placeholder="例如：员工差旅制度" /></label
    ><label class="field upload-zone"
      >原始文件<input
        type="file"
        aria-label="原始文件"
        accept=".pdf,.docx,.txt,.md,.markdown"
        required
        @change="select"
      /><small>PDF、DOCX、TXT、Markdown · 最大 50 MiB</small></label
    ></FormModal
  >
</template>
