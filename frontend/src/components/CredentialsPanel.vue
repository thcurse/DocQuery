<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { useAuth } from '../stores/auth'
import { onBeforeRouteLeave } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NAlert, NPagination } from 'naive-ui'
import { api, collectAllPages } from '../api/client'
import { useAction } from '../composables/common'
import { formatDateTime } from '../domain/display'
import QueryState from './QueryState.vue'
import FormModal from './FormModal.vue'
import SecretModal from './SecretModal.vue'
import StatusTag from './StatusTag.vue'
const props = defineProps<{ tenantId: number; applicationId: number }>()
const { busy, run, confirm } = useAction()
const open = ref(false)
const name = ref('')
const secret = ref('')
const page = ref(1)
const auth = useAuth()
onBeforeRouteLeave(() => !auth.admin || !secret.value)
onUnmounted(() => {
  secret.value = ''
  name.value = ''
})
const query = useQuery({
  queryKey: ['credentials', props.tenantId, props.applicationId],
  queryFn: () =>
    collectAllPages((p, s) => api.listCredentials(props.tenantId, props.applicationId, p, s)),
})
function create() {
  void run(
    async () => {
      if (!name.value.trim()) throw new Error('请输入凭证名称')
      const result = await api.createCredential(
        props.tenantId,
        props.applicationId,
        name.value.trim(),
      )
      secret.value = result.credential
    },
    '凭证已创建',
    () => {
      name.value = ''
      open.value = false
    },
  )
}
</script>
<template>
  <div class="section-heading">
    <h2>应用凭证</h2>
    <NButton type="primary" @click="open = true">生成凭证</NButton>
  </div>
  <NAlert type="info" class="space-bottom"
    >完整凭证只在创建时展示一次，请保存到可信应用的密钥管理位置。</NAlert
  ><QueryState
    :loading="query.isPending.value"
    :error="query.error.value"
    :empty="!query.data.value?.length"
    @retry="query.refetch()"
    ><div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>凭证名称</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>最后使用</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in query.data.value?.slice((page - 1) * 10, page * 10)" :key="c.id">
            <td>
              {{ c.name }}<small>{{ c.keyIdPrefix }}</small>
            </td>
            <td><StatusTag :status="c.status" /></td>
            <td>{{ formatDateTime(c.createdAt) }}</td>
            <td>{{ formatDateTime(c.lastUsedAt) }}</td>
            <td>
              <NButton
                v-if="c.status === '1'"
                type="error"
                quaternary
                @click="
                  confirm('撤销凭证', '撤销不可恢复，此凭证的新请求将立即失去认证能力。', () =>
                    api.revokeCredential(tenantId, applicationId, c.id),
                  )
                "
                >撤销</NButton
              ><span v-else class="muted">不可使用</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <NPagination
      v-model:page="page"
      :page-size="10"
      :item-count="query.data.value?.length || 0"
      class="pagination" /></QueryState
  ><FormModal
    v-if="open"
    title="生成凭证"
    submit-text="生成凭证"
    :busy="busy"
    @close="
      () => {
        open = false
        name = ''
      }
    "
    @submit="create"
    ><label class="field"
      >凭证名称<input
        v-model="name"
        aria-label="凭证名称"
        required
        maxlength="200" /></label></FormModal
  ><SecretModal v-if="secret" :secret="secret" @close="secret = ''" />
</template>
