<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NPagination, NTag } from 'naive-ui'
import { api, collectAllPages } from '../api/client'
import { useIds } from '../composables/common'
import { formatDateTime } from '../domain/display'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
const { tenantId } = useIds()
const page = ref(1)
const size = ref(20)
const blank = () => ({
  applicationId: undefined as number | undefined,
  knowledgeBaseId: undefined as number | undefined,
  operation: '',
  outcome: '',
  requestId: '',
  queryExecutionId: '',
  traceId: '',
})
const draft = reactive(blank())
const filters = ref(blank())
const applications = useQuery({
  queryKey: ['application', 'list', tenantId],
  queryFn: () => collectAllPages((p, s) => api.listApplications(tenantId.value, p, s)),
})
const kbs = useQuery({
  queryKey: ['knowledgeBase', 'list', tenantId],
  queryFn: () => collectAllPages((p, s) => api.listKnowledgeBases(tenantId.value, p, s)),
})
const appNames = computed(() => new Map(applications.data.value?.map((x) => [x.id, x.name])))
const kbNames = computed(() => new Map(kbs.data.value?.map((x) => [x.id, x.name])))
const audits = useQuery({
  queryKey: ['audits', tenantId, page, size, filters],
  queryFn: () =>
    api.listQueryAudits(tenantId.value, {
      ...filters.value,
      page: page.value - 1,
      size: size.value,
    }),
})
watch(size, () => (page.value = 1))
function apply() {
  page.value = 1
  filters.value = { ...draft }
}
function reset() {
  Object.assign(draft, blank())
  apply()
}
</script>
<template>
  <PageHeader title="查询审计" description="追踪应用调用、执行结果与失败原因。" />
  <section class="panel">
    <form @submit.prevent="apply">
      <div class="toolbar">
        <select v-model="draft.applicationId" aria-label="应用">
          <option :value="undefined">全部应用</option>
          <option v-for="a in applications.data.value" :key="a.id" :value="a.id">
            {{ a.name }}
          </option></select
        ><select v-model="draft.knowledgeBaseId" aria-label="知识库">
          <option :value="undefined">全部知识库</option>
          <option v-for="k in kbs.data.value" :key="k.id" :value="k.id">
            {{ k.name }}
          </option></select
        ><select v-model="draft.operation" aria-label="接口">
          <option value="">全部接口</option>
          <option>RETRIEVE</option>
          <option>ANSWER</option></select
        ><select v-model="draft.outcome" aria-label="结果">
          <option value="">全部结果</option>
          <option
            v-for="v in ['SUCCEEDED', 'FAILED', 'REJECTED', 'STARTED', 'INTERRUPTED']"
            :key="v"
          >
            {{ v }}
          </option></select
        ><input
          v-model="draft.requestId"
          placeholder="Request ID"
          aria-label="Request ID"
        /><NButton type="primary" attr-type="submit">筛选</NButton
        ><NButton @click="reset">重置</NButton>
      </div>
      <details class="advanced">
        <summary>更多标识筛选</summary>
        <div class="toolbar">
          <input
            v-model="draft.queryExecutionId"
            placeholder="Query Execution ID"
            aria-label="Query Execution ID"
          /><input v-model="draft.traceId" placeholder="Trace ID" aria-label="Trace ID" />
        </div>
      </details>
    </form>
    <QueryState
      :loading="audits.isPending.value"
      :error="audits.error.value || applications.error.value || kbs.error.value"
      :empty="!audits.data.value?.items.length"
      @retry="
        () => {
          audits.refetch()
          applications.refetch()
          kbs.refetch()
        }
      "
      ><div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>请求</th>
              <th>调用主体</th>
              <th>知识库</th>
              <th>结果</th>
              <th>HTTP</th>
              <th>耗时</th>
              <th>开始时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in audits.data.value?.items" :key="a.id">
              <td>
                <strong>{{ a.operation }}</strong
                ><small>{{ a.requestId }}</small>
              </td>
              <td>
                {{ appNames.get(a.applicationId) || `#${a.applicationId}`
                }}<small>{{ a.actorRef || a.credentialFingerprint }}</small>
              </td>
              <td>{{ kbNames.get(a.knowledgeBaseId) || `#${a.knowledgeBaseId}` }}</td>
              <td>
                <NTag
                  :type="
                    a.outcome === 'SUCCEEDED'
                      ? 'success'
                      : a.outcome === 'FAILED'
                        ? 'error'
                        : 'warning'
                  "
                  size="small"
                  :bordered="false"
                  >{{ a.outcome }}</NTag
                >
              </td>
              <td>{{ a.httpStatus }}</td>
              <td>{{ a.durationMs ?? '—' }} ms</td>
              <td>{{ formatDateTime(a.startedAt) }}</td>
              <td><RouterLink :to="`/query-audits/${a.id}`">查看详情</RouterLink></td>
            </tr>
          </tbody>
        </table>
      </div>
      <NPagination
        v-model:page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50]"
        show-size-picker
        :item-count="audits.data.value?.total || 0"
        class="pagination"
    /></QueryState>
  </section>
</template>
