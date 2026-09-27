<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NInput, NPagination, NAlert } from 'naive-ui'
import { api, collectAllPages } from '../api/client'
import type { ApplicationEnvironment, Application, KnowledgeBase, Tenant } from '../domain/types'
import { environmentLabel, formatDateTime } from '../domain/display'
import { useIds, useAction, validatePassword, environments } from '../composables/common'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
import StatusTag from '../components/StatusTag.vue'
import FormModal from '../components/FormModal.vue'
const props = defineProps<{ kind: 'tenant' | 'application' | 'knowledgeBase' }>()
const { tenantId } = useIds()
const { busy, run } = useAction()
const noun = computed(
  () => ({ tenant: '租户', application: '应用', knowledgeBase: '知识库' })[props.kind],
)
const path = computed(
  () =>
    ({ tenant: '/tenants', application: '/applications', knowledgeBase: '/knowledge-bases' })[
      props.kind
    ],
)
const search = ref('')
const page = ref(1)
const size = ref(12)
const open = ref(false)
const createdAdmin = ref('')
const blank = () => ({
  name: '',
  description: '',
  code: '',
  environment: 'PRODUCTION' as ApplicationEnvironment,
  loginName: '',
  password: '',
})
const form = reactive(blank())
const query = useQuery<(Application | KnowledgeBase | Tenant)[]>({
  queryKey: [props.kind, 'list', tenantId],
  queryFn: () =>
    props.kind === 'tenant'
      ? collectAllPages(api.listTenants)
      : props.kind === 'application'
        ? collectAllPages((p, s) => api.listApplications(tenantId.value, p, s))
        : collectAllPages((p, s) => api.listKnowledgeBases(tenantId.value, p, s)),
})
const filtered = computed(() =>
  (query.data.value || []).filter((x) =>
    `${x.name} ${x.id} ${'code' in x ? x.code : ''}`
      .toLowerCase()
      .includes(search.value.trim().toLowerCase()),
  ),
)
const visible = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value),
)
watch([search, size], () => (page.value = 1))
function close() {
  open.value = false
  Object.assign(form, blank())
}
function create() {
  void run(
    async () => {
      if (!form.name.trim()) throw new Error('请输入名称')
      if (props.kind === 'tenant') {
        validatePassword(form.password)
        const result = await api.createTenant({
          tenantName: form.name.trim(),
          adminLoginName: form.loginName,
          adminPassword: form.password,
        })
        createdAdmin.value = result.initialAdmin.loginName
      } else if (props.kind === 'application')
        await api.createApplication(tenantId.value, {
          name: form.name.trim(),
          code: form.code,
          environment: form.environment,
          description: form.description,
        })
      else
        await api.createKnowledgeBase(tenantId.value, {
          name: form.name.trim(),
          description: form.description,
        })
    },
    `${noun.value}已创建`,
    close,
  )
}
</script>
<template>
  <PageHeader
    :title="`${noun}管理`"
    :description="
      kind === 'knowledgeBase'
        ? '集中管理文档，为你的应用构建可信知识。'
        : kind === 'application'
          ? '连接业务应用，管理访问凭证与知识库授权。'
          : '管理租户空间与管理员，保持业务数据独立。'
    "
    ><NButton type="primary" @click="open = true">＋ 创建{{ noun }}</NButton></PageHeader
  ><NAlert v-if="createdAdmin" type="success" closable @close="createdAdmin = ''"
    >首个租户管理员 {{ createdAdmin }} 已创建。</NAlert
  >
  <section class="panel" :class="{ 'transparent-panel': kind === 'knowledgeBase' }">
    <div class="toolbar">
      <NInput
        v-model:value="search"
        :placeholder="`搜索${noun}名称或 ID`"
        clearable
        class="search-input"
        :input-props="{ 'aria-label': `搜索${noun}` }"
      /><span class="muted">共 {{ filtered.length }} 个{{ noun }}</span
      ><NButton quaternary @click="query.refetch()">刷新</NButton>
    </div>
    <QueryState
      :loading="query.isPending.value"
      :error="query.error.value"
      :empty="filtered.length === 0"
      @retry="query.refetch()"
      ><template #empty
        ><NButton @click="open = true">创建{{ noun }}</NButton></template
      >
      <div v-if="kind === 'knowledgeBase'" class="knowledge-grid">
        <RouterLink
          v-for="item in visible"
          :key="item.id"
          :to="`${path}/${item.id}`"
          class="knowledge-card"
          ><div class="card-top">
            <span class="book-icon">▤</span><StatusTag :status="item.status" />
          </div>
          <h2>{{ item.name }}</h2>
          <p>
            {{
              'description' in item ? item.description || '添加文档，开始构建你的知识空间。' : ''
            }}
          </p>
          <div class="card-bottom">
            <span>{{ formatDateTime(item.updatedAt) }} 更新</span><span>进入知识库 →</span>
          </div></RouterLink
        >
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{{ noun }}</th>
              <th v-if="kind === 'application'">环境</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in visible" :key="item.id">
              <td>
                <RouterLink :to="`${path}/${item.id}`" class="resource-name">{{
                  item.name
                }}</RouterLink
                ><small
                  >ID: {{ item.id }}
                  <template v-if="'code' in item"> · {{ item.code }}</template></small
                >
              </td>
              <td v-if="kind === 'application'">
                {{ 'environment' in item ? environmentLabel(item.environment) : '' }}
              </td>
              <td><StatusTag :status="item.status" /></td>
              <td>{{ formatDateTime(item.createdAt) }}</td>
              <td><RouterLink :to="`${path}/${item.id}`">查看详情</RouterLink></td>
            </tr>
          </tbody>
        </table>
      </div>
      <NPagination
        v-model:page="page"
        v-model:page-size="size"
        :item-count="filtered.length"
        :page-sizes="[12, 24, 48]"
        show-size-picker
        class="pagination"
    /></QueryState>
  </section>
  <FormModal
    v-if="open"
    :title="`创建${noun}`"
    :submit-text="`创建${noun}`"
    :busy="busy"
    @close="close"
    @submit="create"
    ><label v-if="kind === 'application'" class="field"
      >应用编码<input
        v-model="form.code"
        aria-label="应用编码"
        required
        maxlength="64"
        pattern="[a-z0-9][a-z0-9-]{2,63}"
        placeholder="例如：travel-service" /></label
    ><label class="field"
      >{{ noun }}名称<input
        v-model="form.name"
        :aria-label="`${noun}名称`"
        required
        maxlength="200"
        placeholder="输入名称" /></label
    ><label v-if="kind === 'application'" class="field"
      >环境<select v-model="form.environment" aria-label="环境">
        <option v-for="option in environments" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select></label
    ><template v-if="kind === 'tenant'"
      ><label class="field"
        >管理员登录名<input
          v-model="form.loginName"
          aria-label="管理员登录名"
          required
          pattern="[a-z0-9][a-z0-9._-]{2,63}"
          autocomplete="off" /></label
      ><label class="field"
        >初始密码<input
          v-model="form.password"
          type="password"
          aria-label="初始密码"
          required
          minlength="12"
          autocomplete="new-password"
        /><small>至少 12 个字符，最多 72 UTF-8 字节</small></label
      ></template
    ><label v-else class="field"
      >描述<textarea
        v-model="form.description"
        aria-label="描述"
        rows="4"
        maxlength="1000"
        placeholder="简要描述用途"
      /></label
  ></FormModal>
</template>
