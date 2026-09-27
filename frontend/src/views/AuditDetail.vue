<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NAlert, NButton } from 'naive-ui'
import { api } from '../api/client'
import { useIds, useAction, validId } from '../composables/common'
import type { QueryAudit } from '../domain/types'
import { formatDateTime } from '../domain/display'
import { answerStatusLabel } from '../domain/query'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
const { tenantId, id } = useIds()
const { copy } = useAction()
const query = useQuery({
  queryKey: ['audit', tenantId, id],
  queryFn: () => api.getQueryAudit(tenantId.value, id.value),
  enabled: computed(() => validId(id.value)),
})
const groups: { title: string; fields: [keyof QueryAudit, string][] }[] = [
  {
    title: '请求与主体',
    fields: [
      ['requestId', 'Request ID'],
      ['queryExecutionId', 'Query Execution ID'],
      ['applicationId', 'Application ID'],
      ['knowledgeBaseId', 'KnowledgeBase ID'],
      ['credentialFingerprint', 'Credential 指纹'],
      ['actorRef', 'Actor Ref'],
      ['callerTraceId', 'Caller Trace ID'],
      ['querySha256', 'Query SHA-256'],
      ['queryCodePoints', '问题长度'],
      ['idempotencyDisposition', '幂等处理'],
    ],
  },
  {
    title: '执行结果',
    fields: [
      ['outcome', '结果'],
      ['httpStatus', 'HTTP 状态'],
      ['durationMs', '耗时 (ms)'],
      ['requestedMode', '请求模式'],
      ['executedMode', '执行模式'],
      ['degraded', '是否降级'],
      ['failureCategory', '失败类别'],
      ['failureCode', '失败码'],
      ['degradationReason', '降级原因'],
      ['startedAt', '开始时间'],
      ['completedAt', '完成时间'],
      ['snapshotFingerprint', 'Snapshot 指纹'],
    ],
  },
  {
    title: '结果计数',
    fields: [
      ['activeVersionCount', 'Active Versions'],
      ['resultCount', 'Results'],
      ['evidenceCount', 'Evidence'],
      ['citationCount', 'Citations'],
      ['answerStatus', '回答状态'],
      ['toolRounds', 'Tool Rounds'],
      ['toolCalls', 'Tool Calls'],
      ['modelCalls', 'Model Calls'],
      ['canonicalCharacters', 'Canonical 字符'],
    ],
  },
]
function display(key: keyof QueryAudit) {
  const v = query.data.value?.[key]
  if (key === 'answerStatus' && typeof v === 'string') return answerStatusLabel(v)
  return v === null || v === undefined || v === ''
    ? '—'
    : key.endsWith('At')
      ? formatDateTime(String(v))
      : typeof v === 'boolean'
        ? v
          ? '是'
          : '否'
        : String(v)
}
</script>
<template>
  <QueryState
    :loading="query.isPending.value && validId(id)"
    :error="!validId(id) ? new Error('无效审计 ID') : query.error.value"
    @retry="query.refetch()"
    ><template v-if="query.data.value"
      ><PageHeader
        :title="`${query.data.value.operation} 查询审计`"
        :description="`Audit ID：${id}`"
        back="/query-audits"
        back-label="查询审计"
      /><NAlert type="info" title="隐私边界" class="space-bottom"
        >审计只记录摘要、调用主体、结果和计数，不记录凭证、问题原文、回答或引用正文。</NAlert
      >
      <section v-for="g in groups" :key="g.title" class="panel">
        <h2>{{ g.title }}</h2>
        <dl class="details">
          <template v-for="[key, label] in g.fields" :key="key"
            ><dt>{{ label }}</dt>
            <dd>
              {{ display(key)
              }}<NButton
                v-if="(key === 'requestId' || key === 'queryExecutionId') && query.data.value[key]"
                quaternary
                size="tiny"
                :aria-label="`复制 ${label}`"
                @click="copy(String(query.data.value[key]))"
                >复制</NButton
              >
            </dd></template
          >
        </dl>
      </section></template
    ></QueryState
  >
</template>
