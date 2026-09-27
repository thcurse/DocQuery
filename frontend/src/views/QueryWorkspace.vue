<script setup lang="ts">
const newKey = () => crypto.randomUUID()
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NAlert, NDrawer, NDrawerContent } from 'naive-ui'
import { api, collectAllPages, ApiError } from '../api/client'
import type {
  AnswerResponse,
  RetrieveResponse,
  ServiceCallResult,
  RetrievalMode,
  AnswerCitation,
} from '../domain/types'
import { useIds, errorMessage, useAction } from '../composables/common'
import { useAnswerStream } from '../composables/useAnswerStream'
import { AnswerStreamError } from '../api/answerStream'
import { usePageRequest } from '../composables/usePageRequest'
import {
  buildAnswerRequest,
  answerStatusLabel,
  answerFailurePolicy,
  querySnapshot,
  type QuerySnapshot,
} from '../domain/query'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
import CitationList from '../components/CitationList.vue'
const props = defineProps<{ chat: boolean }>()
const { tenantId } = useIds()
const { copy } = useAction()
const execute = usePageRequest()
const streaming = ref(props.chat)
const stream = useAnswerStream()
const resultsPane = ref<HTMLElement>()
const following = ref(true)
function followLatest() {
  following.value = true
  if (resultsPane.value) resultsPane.value.scrollTop = resultsPane.value.scrollHeight
}
function onResultsScroll() {
  const pane = resultsPane.value
  if (pane) following.value = pane.scrollHeight - pane.scrollTop - pane.clientHeight < 80
}
function scrollAnswer() {
  if (following.value)
    void nextTick(() => {
      if (following.value) followLatest()
    })
}
const stageLabels: Record<string, string> = {
  retrieving: '正在检索资料…',
  generating: '正在生成回答，内容尚未完成校验…',
  validating: '正在校验答案与引用…',
  interrupted: '未完成，未经最终校验',
  failed: '回答失败，内容未完成且未经最终校验',
}
const form = reactive({
  applicationId: undefined as number | undefined,
  knowledgeBaseId: undefined as number | undefined,
  credential: '',
  query: '',
  mode: 'HYBRID' as RetrievalMode,
  topK: 5,
  traceId: '',
  actorRef: '',
  operation: props.chat ? 'answer' : 'retrieve',
})
const key = ref<string>(newKey())
const error = ref('')
const drawer = ref(false)
interface Exchange {
  id: string
  question: string
  snapshot: QuerySnapshot
  streaming: boolean
  restart?: boolean
  kbId: number
  result?: ServiceCallResult<AnswerResponse | RetrieveResponse>
  preview?: string
  stage?: string
  failure?: string
  retryable?: boolean
  elapsed: number
}
const exchanges = ref<Exchange[]>([])
const selected = ref<Exchange>()
const citations = computed<AnswerCitation[]>(() =>
  selected.value?.result && 'citations' in selected.value.result.data
    ? selected.value.result.data.citations
    : [],
)
const applications = useQuery({
  queryKey: ['application', 'list', tenantId],
  queryFn: () => collectAllPages((p, s) => api.listApplications(tenantId.value, p, s)),
})
const knowledgeBases = useQuery({
  queryKey: ['knowledgeBase', 'list', tenantId],
  queryFn: () => collectAllPages((p, s) => api.listKnowledgeBases(tenantId.value, p, s)),
})
const grants = useQuery({
  queryKey: ['grants', tenantId, 'application', computed(() => form.applicationId)],
  queryFn: () =>
    collectAllPages((p, s) =>
      api.listGrantsByApplication(tenantId.value, form.applicationId!, p, s),
    ),
  enabled: computed(() => !!form.applicationId),
})
const available = computed(() => {
  const ids = new Set(
    grants.data.value
      ?.filter((g) => g.status === '1' && ['1', '3'].includes(g.permission))
      .map((g) => g.knowledgeBaseId),
  )
  return knowledgeBases.data.value?.filter((k) => k.status === '1' && ids.has(k.id)) || []
})
watch(
  () => form.applicationId,
  () => {
    form.knowledgeBaseId = undefined
  },
)
onUnmounted(() => {
  form.credential = ''
  form.query = ''
  exchanges.value = []
  selected.value = undefined
})
function clear() {
  if (execute.isPending.value) return
  exchanges.value = []
  selected.value = undefined
  form.credential = ''
  error.value = ''
}
watch(
  () =>
    JSON.stringify([
      form.applicationId,
      form.knowledgeBaseId,
      form.query.trim(),
      form.mode,
      form.topK,
      form.operation,
    ]),
  () => {
    key.value = newKey()
  },
  { flush: 'sync' },
)
function submit() {
  runRequest(querySnapshot(form), key.value, streaming.value)
}
function repeat(exchange: Exchange, fresh: boolean) {
  runRequest(exchange.snapshot, fresh ? newKey() : exchange.id, exchange.streaming)
}
function runRequest(snapshot: QuerySnapshot, requestKey: string, streamMode: boolean) {
  if (execute.isPending.value) return
  if (
    !snapshot.query.trim() ||
    !form.credential.trim() ||
    snapshot.applicationId !== form.applicationId ||
    !snapshot.knowledgeBaseId ||
    !available.value.some((k) => k.id === snapshot.knowledgeBaseId)
  ) {
    error.value = '请选择有读取权限的知识库，并输入凭证和问题'
    return
  }
  const idempotencyKey = requestKey
  const credential = form.credential.trim()
  const started = performance.now()
  const current = reactive<Exchange>({
    id: idempotencyKey,
    snapshot,
    streaming: streamMode,
    question: snapshot.query.trim(),
    kbId: snapshot.knowledgeBaseId!,
    preview: '',
    stage: 'retrieving',
    elapsed: 0,
  })
  const useStreaming = snapshot.operation === 'answer' && streamMode
  void execute.run(
    async (signal) => {
      const body = buildAnswerRequest(snapshot)
      const context = {
        traceId: snapshot.traceId.trim() || undefined,
        actorRef: snapshot.actorRef.trim() || undefined,
      }
      const result = useStreaming
        ? await stream.run([
            snapshot.knowledgeBaseId!,
            credential,
            idempotencyKey,
            body,
            context,
            signal,
            {
              progress: (stage) => {
                if (!signal.aborted) current.stage = stage
              },
              delta: (text) => {
                if (!signal.aborted) {
                  current.preview += text
                  scrollAnswer()
                }
              },
            },
          ])
        : snapshot.operation === 'answer'
          ? await api.answer(
              snapshot.knowledgeBaseId!,
              credential,
              idempotencyKey,
              body,
              context,
              signal,
            )
          : await api.retrieve(
              snapshot.knowledgeBaseId!,
              credential,
              idempotencyKey,
              body,
              context,
              signal,
            )
      return {
        id: idempotencyKey,
        question: body.query,
        kbId: snapshot.knowledgeBaseId!,
        result,
        elapsed: Math.round(performance.now() - started),
      }
    },
    {
      onStart: () => {
        error.value = ''
        selected.value = undefined
        if (!props.chat) exchanges.value = []
        exchanges.value = exchanges.value.filter((item) => item.id !== idempotencyKey)
        exchanges.value.push(current)
        following.value = true
        scrollAnswer()
      },
      onSuccess: (result) => {
        current.result = result.result
        current.elapsed = result.elapsed
        current.stage = 'completed'
        current.preview = ''
        selected.value = current
        key.value = newKey()
        if (props.chat && form.query.trim() === snapshot.query) form.query = ''
        scrollAnswer()
      },
      onError: (e) => {
        current.elapsed = Math.round(performance.now() - started)
        const policy = answerFailurePolicy(e instanceof ApiError ? e.code : 'ANSWER_INTERRUPTED')
        current.stage =
          !(e instanceof ApiError) || e.code === 'ANSWER_INTERRUPTED' ? 'interrupted' : 'failed'
        current.retryable = policy.retryable && (!(e instanceof AnswerStreamError) || e.retryable)
        current.restart = policy.restart
        current.failure = policy.restart
          ? '请求参数或服务配置已变化，请重新发起。'
          : errorMessage(e)
        if (policy.clearPreview || (e instanceof AnswerStreamError && e.clearPreview)) {
          current.preview = ''
          current.stage = 'failed'
        }
        error.value = current.failure
      },
      onCancel: () => {
        current.stage = 'interrupted'
        current.failure = '已停止生成，部分内容未经最终校验'
        current.elapsed = Math.round(performance.now() - started)
      },
    },
  )
}
</script>
<template>
  <PageHeader
    :title="chat ? '知识库问答' : 'API 调试'"
    :description="
      chat ? '向文档提问，在引用中核实每一个答案。' : '使用应用凭证验证检索与回答接口。'
    "
    ><NButton :disabled="execute.isPending.value" @click="clear"
      >清空{{ chat ? '对话' : '结果' }}</NButton
    ><RouterLink to="/guide"><NButton>使用说明</NButton></RouterLink></PageHeader
  ><QueryState
    :loading="applications.isPending.value || knowledgeBases.isPending.value"
    :error="applications.error.value || knowledgeBases.error.value"
    @retry="
      () => {
        applications.refetch()
        knowledgeBases.refetch()
      }
    "
    ><div class="query-layout" :class="{ 'chat-layout': chat }">
      <section class="panel query-settings">
        <h2>{{ chat ? '选择知识范围' : '请求配置' }}</h2>
        <form @submit.prevent="submit">
          <fieldset :disabled="execute.isPending.value">
            <label v-if="!chat" class="field"
              >接口<select v-model="form.operation" aria-label="接口">
                <option value="retrieve">/retrieve</option>
                <option value="answer">/answer</option>
              </select></label
            ><label v-if="!chat && form.operation === 'answer'" class="field">
              响应方式<select v-model="streaming" aria-label="响应方式">
                <option :value="false">普通回答</option>
                <option :value="true">流式回答</option>
              </select> </label
            ><label class="field"
              >Application<select v-model="form.applicationId" aria-label="Application" required>
                <option :value="undefined" disabled>选择应用</option>
                <option
                  v-for="a in applications.data.value"
                  :key="a.id"
                  :value="a.id"
                  :disabled="a.status !== '1'"
                >
                  {{ a.name }} · {{ a.code }}
                </option>
              </select></label
            ><label class="field"
              >KnowledgeBase<select
                v-model="form.knowledgeBaseId"
                aria-label="KnowledgeBase"
                :disabled="!form.applicationId || grants.isFetching.value"
                required
              >
                <option :value="undefined" disabled>选择已获读取授权的知识库</option>
                <option v-for="k in available" :key="k.id" :value="k.id">{{ k.name }}</option>
              </select></label
            ><NAlert v-if="grants.error.value" type="error"
              >{{ errorMessage(grants.error.value)
              }}<NButton @click="grants.refetch()">重试</NButton></NAlert
            ><label class="field"
              >Application Credential<input
                v-model="form.credential"
                aria-label="Application Credential"
                type="password"
                required
                autocomplete="off"
                placeholder="dq_app_..."
              /><small>仅保存在本页内存，离开页面后清除。</small></label
            >
            <details class="advanced">
              <summary>高级设置</summary>
              <label class="field"
                >检索模式<select v-model="form.mode" aria-label="检索模式">
                  <option>HYBRID</option>
                  <option>KEYWORD</option>
                  <option>SEMANTIC</option>
                </select></label
              ><label class="field"
                >Top K<input
                  v-model.number="form.topK"
                  aria-label="Top K"
                  type="number"
                  required
                  min="1"
                  max="20" /></label
              ><template v-if="!chat"
                ><label class="field"
                  >Trace ID<input v-model="form.traceId" aria-label="Trace ID" /></label
                ><label class="field"
                  >Actor Ref<input v-model="form.actorRef" aria-label="Actor Ref" /></label
                ><label class="field"
                  >Idempotency-Key<input
                    v-model="key"
                    aria-label="Idempotency-Key"
                    required /></label
                ><NButton @click="key = newKey()">生成新幂等键</NButton></template
              >
            </details>
            <label class="field"
              >问题<textarea
                v-model="form.query"
                aria-label="问题"
                required
                maxlength="2000"
                rows="4"
                placeholder="例如：差旅住宿报销标准是什么？"
              /></label
            ><NButton type="primary" block attr-type="submit" :loading="execute.isPending.value">{{
              chat ? '发送问题' : '发送真实请求'
            }}</NButton>
          </fieldset>
          <NButton v-if="execute.isPending.value" block class="space-top" @click="execute.cancel"
            >停止生成</NButton
          >
        </form>
      </section>
      <section
        ref="resultsPane"
        class="panel query-results"
        tabindex="0"
        aria-label="回答结果"
        @scroll="onResultsScroll"
      >
        <NAlert v-if="error" type="error" title="请求失败" class="space-bottom">{{ error }}</NAlert>
        <div v-if="!exchanges.length && !execute.isPending.value" class="query-welcome">
          <div class="answer-icon">✧</div>
          <h2>{{ chat ? '你的知识，随时可问' : '准备好发送第一个请求' }}</h2>
          <p>选择应用和知识库，输入问题开始探索。<br />回答将附带可追溯的文档来源。</p>
        </div>
        <article v-for="exchange in exchanges" :key="exchange.id" class="exchange">
          <div class="question-bubble">{{ exchange.question }}</div>
          <div class="answer-meta">
            <span class="answer-avatar">D</span><strong>DocQuery</strong
            ><small>{{ exchange.elapsed }} ms</small>
          </div>
          <template v-if="exchange.result">
            <NAlert v-if="exchange.result.data.degraded" type="warning"
              >已降级为 {{ exchange.result.data.executedMode }}：{{
                exchange.result.data.degradationReason
              }}</NAlert
            ><template v-if="'answer' in exchange.result.data"
              ><small>回答状态：{{ answerStatusLabel(exchange.result.data.status) }}</small>
              <p class="answer-text">
                {{
                  exchange.result.data.answer ||
                  (exchange.result.data.status === 'INSUFFICIENT_EVIDENCE'
                    ? '现有文档中的证据不足以回答此问题。'
                    : '暂无回答')
                }}
              </p>
              <NButton
                v-if="exchange.result.data.citations.length"
                size="small"
                @click="
                  () => {
                    selected = exchange
                    drawer = true
                  }
                "
                >查看 {{ exchange.result.data.citations.length }} 条引用</NButton
              ></template
            ><template v-else
              ><div v-if="!exchange.result.data.results.length" class="state">没有找到相关文档</div>
              <article
                v-for="hit in exchange.result.data.results"
                :key="`${hit.documentVersionId}-${hit.rank}`"
                class="retrieval-hit"
              >
                <RouterLink :to="`/knowledge-bases/${exchange.kbId}/documents/${hit.documentId}`"
                  ><strong>{{ hit.rank }}. {{ hit.documentName }}</strong></RouterLink
                ><small
                  >v{{ hit.versionNo }} · {{ hit.headingPath.join(' / ') }} ·
                  {{ hit.channels.join(' / ') }}</small
                >
                <div v-for="e in hit.evidence" :key="e.blockId">
                  <p class="pre-wrap">{{ e.text }}</p>
                  <div v-for="(fragment, index) in e.keywordHighlights" :key="index">
                    <template v-for="(segment, i) in fragment.segments" :key="i"
                      ><mark v-if="segment.matched">{{ segment.text }}</mark
                      ><span v-else>{{ segment.text }}</span></template
                    >
                  </div>
                  <small v-if="e.sourcePosition?.pageNumber"
                    >第 {{ e.sourcePosition.pageNumber }} 页</small
                  ><small v-if="e.truncated">内容已截断</small>
                </div>
              </article></template
            >
            <NButton
              v-if="!execute.isPending.value && exchange.snapshot.operation === 'answer'"
              size="small"
              @click="repeat(exchange, true)"
              >重新回答</NButton
            >
            <details v-if="!chat" class="response-details">
              <summary>响应详情</summary>
              <dl class="details">
                <dt>Request ID</dt>
                <dd data-testid="request-id">{{ exchange.result.requestId || '—' }}</dd>
                <dt>Query Execution ID</dt>
                <dd>{{ exchange.result.data.queryExecutionId }}</dd>
                <dt>请求 / 执行模式</dt>
                <dd>
                  {{ exchange.result.data.requestedMode }} / {{ exchange.result.data.executedMode }}
                </dd>
              </dl>
              <NButton size="small" @click="copy(JSON.stringify(exchange.result.data, null, 2))"
                >复制 JSON</NButton
              >
              <pre>{{ JSON.stringify(exchange.result.data, null, 2) }}</pre>
            </details>
          </template>
          <template v-else>
            <p role="status" class="stream-status">
              {{ stageLabels[exchange.stage || 'retrieving'] }}
            </p>
            <p v-if="exchange.preview" class="answer-text">{{ exchange.preview }}</p>
            <p v-if="exchange.failure" class="muted">{{ exchange.failure }}</p>
            <NButton
              v-if="!execute.isPending.value && !exchange.restart && exchange.retryable !== false"
              @click="repeat(exchange, false)"
              >重试回答</NButton
            >
            <NButton
              v-if="!execute.isPending.value && exchange.restart"
              @click="repeat(exchange, true)"
              >重新发起</NButton
            >
          </template>
        </article>
        <NButton
          v-if="!following && exchanges.length"
          class="follow-latest"
          size="small"
          @click="followLatest"
          >回到最新</NButton
        >
      </section>
      <aside v-if="chat" class="panel citation-sidebar">
        <h2>引用来源</h2>
        <CitationList :citations="citations" :kb-id="selected?.kbId || 0" />
      </aside></div></QueryState
  ><NDrawer v-model:show="drawer" :width="480" class="responsive-drawer"
    ><NDrawerContent title="引用来源" closable
      ><CitationList :citations="citations" :kb-id="selected?.kbId || 0" /></NDrawerContent
  ></NDrawer>
</template>
