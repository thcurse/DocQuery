<script setup lang="ts">
const newKey = () => crypto.randomUUID()
import { computed, ref, watch } from 'vue'
import { useQuery, keepPreviousData } from '@tanstack/vue-query'
import { NButton, NAlert, NPagination, NDrawer, NDrawerContent } from 'naive-ui'
import { api } from '../api/client'
import { queryClient } from '../queryClient'
import { useIds, useAction, validId } from '../composables/common'
import { formatDateTime, formatBytes, sourceFormatLabel } from '../domain/display'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
import StatusTag from '../components/StatusTag.vue'
import UploadModal from '../components/UploadModal.vue'
const { tenantId, id, kbId } = useIds()
const { busy, run, confirm } = useAction()
const page = ref(1)
const size = ref(20)
const upload = ref(false)
const jobId = ref<number>()
const valid = computed(() => validId(id.value) && validId(kbId.value))
const document = useQuery({
  queryKey: ['document', tenantId, kbId, id],
  queryFn: () => api.getDocument(tenantId.value, kbId.value, id.value),
  enabled: valid,
  refetchInterval: (q) =>
    q.state.data &&
    (q.state.data.latestVersion?.status === '1' ||
      (q.state.data.documentStatus === '2' && q.state.data.latestDeletionJob?.status !== '4'))
      ? 3000
      : false,
})
const versions = useQuery({
  queryKey: ['versions', tenantId, kbId, id, page, size],
  queryFn: () =>
    api.listDocumentVersions(tenantId.value, kbId.value, id.value, page.value - 1, size.value),
  enabled: document.isSuccess,
  placeholderData: keepPreviousData,
  refetchInterval: (q) =>
    document.data.value?.latestVersion?.status === '1' ||
    q.state.data?.items.some((v) => v.status === '1')
      ? 3000
      : false,
})
watch(size, () => (page.value = 1))
watch(
  () => [
    document.data.value?.latestVersion?.documentVersionId,
    document.data.value?.latestVersion?.status,
    document.data.value?.activeVersion?.documentVersionId,
    document.data.value?.documentStatus,
  ],
  () => {
    void queryClient.invalidateQueries({
      queryKey: ['versions', tenantId.value, kbId.value, id.value],
    })
  },
)
const job = useQuery({
  queryKey: ['job', tenantId, jobId],
  queryFn: () => api.getProcessingJob(tenantId.value, jobId.value!),
  enabled: computed(() => jobId.value !== undefined),
  refetchInterval: (q) =>
    q.state.data && ['1', '2'].includes(q.state.data.job.status) ? 3000 : false,
})
const canRebuild = computed(
  () =>
    document.data.value?.documentStatus === '1' &&
    document.data.value.activeVersion?.status === '2' &&
    document.data.value.latestVersion?.status !== '1',
)
function rebuild() {
  confirm(
    '重建文档',
    '将复用原文件重新解析并生成检索数据，可能产生供应商费用；成功后才替换当前版本。',
    async () => {
      await api.rebuildDocument(tenantId.value, kbId.value, id.value, newKey())
      page.value = 1
    },
  )
}
function remove() {
  confirm('删除文档', '将异步清理原文、派生文件和检索数据，完成后不可恢复。', () =>
    api.deleteDocument(tenantId.value, kbId.value, id.value, newKey()),
  )
}
</script>
<template>
  <QueryState
    :loading="document.isPending.value && valid"
    :error="!valid ? new Error('无效文档 ID') : document.error.value"
    @retry="document.refetch()"
    ><template v-if="document.data.value"
      ><PageHeader
        :title="document.data.value.name"
        :description="`Document ID：${id}`"
        :back="`/knowledge-bases/${kbId}`"
        back-label="知识库文档"
        ><template v-if="document.data.value.documentStatus !== '3'"
          ><NButton
            :disabled="document.data.value.documentStatus !== '1' || busy"
            @click="upload = true"
            >上传新版本</NButton
          ><NButton :disabled="!canRebuild" :loading="busy" @click="rebuild">重建</NButton
          ><NButton
            v-if="document.data.value.latestDeletionJob?.status === '4'"
            type="error"
            :loading="busy"
            @click="
              run(() => api.retryDocumentDeletion(tenantId, kbId, id, newKey()), '删除重试已受理')
            "
            >重试删除</NButton
          ><NButton
            v-else
            type="error"
            secondary
            :disabled="document.data.value.documentStatus !== '1'"
            :loading="busy"
            @click="remove"
            >删除文档</NButton
          ></template
        ></PageHeader
      ><NAlert v-if="document.data.value.documentStatus === '2'" type="warning" class="space-bottom"
        >文档正在删除，暂不可上传新版本。</NAlert
      ><NAlert
        v-if="document.data.value.latestDeletionJob?.status === '4'"
        type="error"
        class="space-bottom"
        >删除失败：{{
          document.data.value.latestDeletionJob.failureMessage ||
          document.data.value.latestDeletionJob.failureCode
        }}</NAlert
      ><NAlert
        v-if="document.data.value.latestVersion?.status === '3'"
        type="error"
        class="space-bottom"
        >处理失败：{{
          document.data.value.latestVersion.failureMessage ||
          document.data.value.latestVersion.failureCode
        }}</NAlert
      >
      <section class="panel summary">
        <StatusTag :status="document.data.value.documentStatus" kind="document" /><span
          >当前生效版本：{{
            document.data.value.activeVersion
              ? `v${document.data.value.activeVersion.versionNo}`
              : '暂无'
          }}</span
        ><span
          >最新版本：{{
            document.data.value.latestVersion
              ? `v${document.data.value.latestVersion.versionNo}`
              : '暂无'
          }}</span
        ><span>更新于 {{ formatDateTime(document.data.value.updatedAt) }}</span>
      </section>
      <section class="panel">
        <div class="section-heading">
          <h2>版本与处理记录</h2>
          <NButton
            @click="
              () => {
                document.refetch()
                versions.refetch()
              }
            "
            >刷新</NButton
          >
        </div>
        <QueryState
          :loading="versions.isPending.value"
          :error="versions.error.value"
          :empty="!versions.data.value?.items.length"
          @retry="versions.refetch()"
          ><div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>版本</th>
                  <th>状态</th>
                  <th>文件</th>
                  <th>处理任务</th>
                  <th>就绪 / 失败时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="v in versions.data.value?.items" :key="v.documentVersionId">
                  <td>
                    v{{ v.versionNo }}<small v-if="v.active" class="primary-text">当前生效</small>
                  </td>
                  <td>
                    <StatusTag :status="v.status" kind="version" /><small v-if="v.failureMessage">{{
                      v.failureMessage
                    }}</small>
                  </td>
                  <td>
                    {{ v.originalFilename
                    }}<small
                      >{{ sourceFormatLabel(v.sourceFormat) }} ·
                      {{ formatBytes(v.sourceSizeBytes) }}</small
                    >
                  </td>
                  <td>
                    <NButton
                      v-if="v.latestProcessingJob"
                      text
                      @click="jobId = v.latestProcessingJob.processingJobId"
                      >#{{ v.latestProcessingJob.processingJobId }} ·
                      <StatusTag :status="v.latestProcessingJob.status" kind="job"
                    /></NButton>
                  </td>
                  <td>{{ formatDateTime(v.readyAt || v.failedAt) }}</td>
                  <td>
                    <NButton
                      v-if="
                        v.status === '3' &&
                        v.failureRetryable &&
                        v.latestProcessingJob &&
                        document.data.value.documentStatus === '1'
                      "
                      :loading="busy"
                      @click="
                        run(
                          () =>
                            api.retryProcessingJob(
                              tenantId,
                              v.latestProcessingJob!.processingJobId,
                              newKey(),
                            ),
                          '重试已受理',
                        )
                      "
                      >重试处理</NButton
                    >
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <NPagination
            v-model:page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50, 100]"
            :item-count="versions.data.value?.total || 0"
            show-size-picker
            class="pagination"
        /></QueryState></section></template></QueryState
  ><UploadModal
    v-if="upload"
    :tenant-id="tenantId"
    :knowledge-base-id="kbId"
    :document-id="id"
    @close="upload = false"
    @uploaded="page = 1"
  /><NDrawer
    :show="jobId !== undefined"
    :width="620"
    class="responsive-drawer"
    @update:show="jobId = undefined"
    ><NDrawerContent :title="`处理任务 #${jobId}`" closable
      ><QueryState :loading="job.isPending.value" :error="job.error.value" @retry="job.refetch()"
        ><template v-if="job.data.value"
          ><dl class="details">
            <dt>状态</dt>
            <dd><StatusTag :status="job.data.value.job.status" kind="job" /></dd>
            <dt>任务类型</dt>
            <dd>{{ job.data.value.job.jobType }}</dd>
            <dt>当前尝试</dt>
            <dd>{{ job.data.value.job.attemptNo }}</dd>
            <dt>失败码</dt>
            <dd>{{ job.data.value.job.failureCode || '—' }}</dd>
            <dt>失败信息</dt>
            <dd>{{ job.data.value.job.failureMessage || '—' }}</dd>
            <dt>可重试</dt>
            <dd>{{ job.data.value.job.failureRetryable ? '是' : '否' }}</dd>
          </dl>
          <h3>尝试历史</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>任务</th>
                  <th>尝试</th>
                  <th>状态</th>
                  <th>结束时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="attempt in job.data.value.attemptHistory" :key="attempt.processingJobId">
                  <td>{{ attempt.processingJobId }}</td>
                  <td>{{ attempt.attemptNo }}</td>
                  <td><StatusTag :status="attempt.status" kind="job" /></td>
                  <td>{{ formatDateTime(attempt.finishedAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div></template
        ></QueryState
      ></NDrawerContent
    ></NDrawer
  >
</template>
