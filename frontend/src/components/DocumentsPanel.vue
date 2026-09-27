<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NPagination, NInput } from 'naive-ui'
import { api } from '../api/client'
import { formatDateTime } from '../domain/display'
import { useAction } from '../composables/common'
import QueryState from './QueryState.vue'
import StatusTag from './StatusTag.vue'
import UploadModal from './UploadModal.vue'
const props = defineProps<{ tenantId: number; knowledgeBaseId: number }>()
const { copy } = useAction()
const page = ref(1)
const size = ref(10)
const upload = ref(false)
const search = ref('')
const filters = reactive({ name: '', documentStatus: '', latestVersionStatus: '' })
watch(
  [() => filters.name, () => filters.documentStatus, () => filters.latestVersionStatus, size],
  () => (page.value = 1),
)
const query = useQuery({
  queryKey: ['documents', props.tenantId, props.knowledgeBaseId, page, size, filters],
  queryFn: () =>
    api.listDocuments(props.tenantId, props.knowledgeBaseId, {
      ...filters,
      page: page.value - 1,
      size: size.value,
    }),
  refetchInterval: (q) =>
    q.state.data?.items.some(
      (d) =>
        d.latestVersion?.status === '1' ||
        (d.documentStatus === '2' && d.latestDeletionJob?.status !== '4'),
    )
      ? 3000
      : false,
})
</script>
<template>
  <div class="section-heading">
    <div>
      <h2>文档</h2>
      <p class="muted">上传文档，处理完成后即可检索与问答。</p>
    </div>
    <NButton type="primary" @click="upload = true">＋ 上传文档</NButton>
  </div>
  <form class="toolbar" @submit.prevent="filters.name = search.trim()">
    <NInput
      v-model:value="search"
      placeholder="按文档名称筛选"
      class="search-input"
      clearable
    /><select v-model="filters.documentStatus" aria-label="文档状态">
      <option value="">全部文档状态</option>
      <option value="1">可用</option>
      <option value="2">删除中</option>
      <option value="3">已删除</option></select
    ><select v-model="filters.latestVersionStatus" aria-label="最新版本状态">
      <option value="">全部版本状态</option>
      <option value="1">处理中</option>
      <option value="2">已就绪</option>
      <option value="3">失败</option></select
    ><NButton attr-type="submit">筛选</NButton><NButton @click="query.refetch()">刷新</NButton>
  </form>
  <QueryState
    :loading="query.isPending.value"
    :error="query.error.value"
    :empty="query.data.value?.items.length === 0"
    @retry="query.refetch()"
    ><template #empty><NButton @click="upload = true">上传第一份文档</NButton></template>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>文档</th>
            <th>文档状态</th>
            <th>当前生效版本</th>
            <th>最新版本</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in query.data.value?.items" :key="d.documentId">
            <td>
              <RouterLink
                :to="`/knowledge-bases/${knowledgeBaseId}/documents/${d.documentId}`"
                class="resource-name"
                >{{ d.name }}</RouterLink
              ><small
                >Document ID: {{ d.documentId }}
                <button
                  class="text-button"
                  :aria-label="`复制 Document ID ${d.documentId}`"
                  @click="copy(String(d.documentId))"
                >
                  复制
                </button></small
              >
            </td>
            <td><StatusTag :status="d.documentStatus" kind="document" /></td>
            <td>
              <template v-if="d.activeVersion"
                >v{{ d.activeVersion.versionNo }}
                <StatusTag :status="d.activeVersion.status" kind="version" /></template
              ><span v-else class="muted">暂无</span>
            </td>
            <td>
              <template v-if="d.latestVersion"
                >v{{ d.latestVersion.versionNo }}
                <StatusTag :status="d.latestVersion.status" kind="version"
              /></template>
            </td>
            <td>{{ formatDateTime(d.updatedAt) }}</td>
            <td>
              <RouterLink :to="`/knowledge-bases/${knowledgeBaseId}/documents/${d.documentId}`"
                >查看详情</RouterLink
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <NPagination
      v-model:page="page"
      v-model:page-size="size"
      :page-sizes="[10, 20, 50]"
      show-size-picker
      :item-count="query.data.value?.total || 0"
      class="pagination" /></QueryState
  ><UploadModal
    v-if="upload"
    :tenant-id="tenantId"
    :knowledge-base-id="knowledgeBaseId"
    @close="upload = false"
  />
</template>
