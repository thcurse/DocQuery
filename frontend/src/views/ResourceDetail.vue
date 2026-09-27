<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NTabs, NTabPane, NAlert } from 'naive-ui'
import { api } from '../api/client'
import type { ApplicationEnvironment, Application, KnowledgeBase } from '../domain/types'
import { useIds, useAction, validId, environments } from '../composables/common'
import { formatDateTime, environmentLabel } from '../domain/display'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
import StatusTag from '../components/StatusTag.vue'
import FormModal from '../components/FormModal.vue'
import GrantsPanel from '../components/GrantsPanel.vue'
import CredentialsPanel from '../components/CredentialsPanel.vue'
import DocumentsPanel from '../components/DocumentsPanel.vue'
const props = defineProps<{ kind: 'application' | 'knowledgeBase' }>()
const isApp = props.kind === 'application'
const noun = isApp ? '应用' : '知识库'
const activeTab = ref(isApp ? 'credentials' : 'documents')
function tabProps(name: string) {
  return {
    role: 'tab',
    tabindex: 0,
    'aria-selected': activeTab.value === name,
    onKeydown: (event: KeyboardEvent) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault()
        activeTab.value = name
      }
    },
  }
}
const { tenantId, id } = useIds()
const { busy, run, confirm, copy } = useAction()
const editing = ref(false)
const query = useQuery<Application | KnowledgeBase>({
  queryKey: [props.kind, 'detail', tenantId, id],
  queryFn: () =>
    isApp
      ? api.getApplication(tenantId.value, id.value)
      : api.getKnowledgeBase(tenantId.value, id.value),
  enabled: computed(() => validId(id.value)),
})
const form = reactive({
  name: '',
  description: '',
  environment: 'DEVELOPMENT' as ApplicationEnvironment,
})
function edit() {
  const data = query.data.value!
  form.name = data.name
  form.description = data.description || ''
  if ('environment' in data) form.environment = data.environment
  editing.value = true
}
function save() {
  void run(
    async () => {
      if (!form.name.trim()) throw new Error('请输入名称')
      if (isApp)
        await api.updateApplication(tenantId.value, id.value, { ...form, name: form.name.trim() })
      else
        await api.updateKnowledgeBase(tenantId.value, id.value, {
          name: form.name.trim(),
          description: form.description,
        })
    },
    '已保存',
    () => (editing.value = false),
  )
}
function toggle() {
  const status = query.data.value!.status === '1' ? '2' : '1'
  confirm(
    `${status === '2' ? '停用' : '启用'}${noun}`,
    '停用后新的应用查询将被拒绝，已有内容会保留。',
    () =>
      isApp
        ? api.updateApplication(tenantId.value, id.value, { status })
        : api.updateKnowledgeBase(tenantId.value, id.value, { status }),
  )
}
</script>
<template>
  <QueryState
    :loading="query.isPending.value && validId(id)"
    :error="!validId(id) ? new Error('无效资源 ID') : query.error.value"
    @retry="query.refetch()"
    ><template v-if="query.data.value"
      ><PageHeader
        :title="query.data.value.name"
        :description="query.data.value.description || `管理${noun}内容与访问权限`"
        :back="isApp ? '/applications' : '/knowledge-bases'"
        :back-label="`${noun}管理`"
        ><NButton class="resource-action" type="primary" secondary @click="copy(String(id))">
          <template #icon>
            <svg class="action-icon" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="8" y="8" width="12" height="12" rx="2" />
              <path d="M16 8V4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v11a1 1 0 0 0 1 1h4" />
            </svg>
          </template>
          复制 ID</NButton
        ><NButton v-if="isApp" class="resource-action" type="primary" @click="edit">
          <template #icon>
            <svg class="action-icon" viewBox="0 0 24 24" aria-hidden="true">
              <path d="m15 5 4 4M4 20l4-1L20 7a2.83 2.83 0 0 0-4-4L4 15v5Z" />
            </svg>
          </template>
          编辑</NButton
        ></PageHeader
      ><NAlert v-if="query.data.value.status === '2'" type="warning" class="space-bottom"
        >{{ noun }}已停用</NAlert
      >
      <section class="panel">
        <NTabs type="line" animated v-model:value="activeTab"
          ><NTabPane v-if="!isApp" name="documents" :tab-props="tabProps('documents')" tab="文档"
            ><DocumentsPanel :tenant-id="tenantId" :knowledge-base-id="id" /></NTabPane
          ><NTabPane
            v-if="isApp"
            name="credentials"
            :tab-props="tabProps('credentials')"
            tab="应用凭证"
            display-directive="show"
            ><CredentialsPanel :tenant-id="tenantId" :application-id="id" /></NTabPane
          ><NTabPane
            name="grants"
            :tab-props="tabProps('grants')"
            :tab="isApp ? '知识库授权' : '应用授权'"
            ><GrantsPanel :tenant-id="tenantId" :resource-id="id" :kind="kind" /></NTabPane
          ><NTabPane name="settings" :tab-props="tabProps('settings')" tab="设置"
            ><div class="section-heading">
              <h2>基本信息</h2>
              <div class="actions">
                <NButton @click="edit">编辑</NButton
                ><NButton :loading="busy" @click="toggle"
                  >{{ query.data.value.status === '1' ? '停用' : '启用' }}{{ noun }}</NButton
                >
              </div>
            </div>
            <dl class="details">
              <dt>名称</dt>
              <dd>{{ query.data.value.name }}</dd>
              <dt>状态</dt>
              <dd><StatusTag :status="query.data.value.status" /></dd>
              <dt>ID</dt>
              <dd>{{ id }}</dd>
              <template v-if="'code' in query.data.value"
                ><dt>稳定编码</dt>
                <dd>{{ query.data.value.code }}</dd>
                <dt>环境</dt>
                <dd>{{ environmentLabel(query.data.value.environment) }}</dd></template
              >
              <dt>描述</dt>
              <dd>{{ query.data.value.description || '暂无描述' }}</dd>
              <dt>更新时间</dt>
              <dd>{{ formatDateTime(query.data.value.updatedAt) }}</dd>
            </dl></NTabPane
          ></NTabs
        >
      </section></template
    ></QueryState
  ><FormModal
    v-if="editing"
    :title="`编辑${noun}`"
    :busy="busy"
    @close="editing = false"
    @submit="save"
    ><label class="field"
      >{{ noun }}名称<input
        v-model="form.name"
        :aria-label="`${noun}名称`"
        required
        maxlength="200" /></label
    ><label v-if="isApp" class="field"
      >环境<select v-model="form.environment" aria-label="环境">
        <option v-for="e in environments" :key="e.value" :value="e.value">{{ e.label }}</option>
      </select></label
    ><label class="field"
      >描述<textarea
        v-model="form.description"
        aria-label="描述"
        maxlength="1000"
        rows="4"
      /></label
  ></FormModal>
</template>
