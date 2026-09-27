<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NPagination } from 'naive-ui'
import { api, collectAllPages } from '../api/client'
import type { GrantPermission } from '../domain/types'
import { permissionLabel, formatDateTime } from '../domain/display'
import { useAction, permissions } from '../composables/common'
import QueryState from './QueryState.vue'
import StatusTag from './StatusTag.vue'
import FormModal from './FormModal.vue'
const props = defineProps<{
  tenantId: number
  resourceId: number
  kind: 'application' | 'knowledgeBase'
}>()
const isApp = props.kind === 'application'
const { busy, run, confirm } = useAction()
const open = ref(false)
const targetId = ref<number>()
const permission = ref<GrantPermission>('1')
const page = ref(1)
const grants = useQuery({
  queryKey: ['grants', props.tenantId, props.kind, props.resourceId],
  queryFn: () =>
    collectAllPages((p, s) =>
      isApp
        ? api.listGrantsByApplication(props.tenantId, props.resourceId, p, s)
        : api.listGrantsByKnowledgeBase(props.tenantId, props.resourceId, p, s),
    ),
})
const resources = useQuery({
  queryKey: [isApp ? 'knowledgeBase' : 'application', 'list', props.tenantId],
  queryFn: () =>
    isApp
      ? collectAllPages((p, s) => api.listKnowledgeBases(props.tenantId, p, s))
      : collectAllPages((p, s) => api.listApplications(props.tenantId, p, s)),
})
const names = computed(() => new Map(resources.data.value?.map((x) => [x.id, x.name])))
const ids = (target: number): [number, number] =>
  isApp ? [props.resourceId, target] : [target, props.resourceId]
function save() {
  void run(
    async () => {
      if (!targetId.value) throw new Error('请选择授权对象')
      await api.upsertGrant(props.tenantId, ...ids(targetId.value), permission.value)
    },
    '授权已保存',
    () => (open.value = false),
  )
}
</script>
<template>
  <div class="section-heading">
    <h2>{{ isApp ? '知识库授权' : '已授权应用' }}</h2>
    <NButton
      type="primary"
      @click="
        () => {
          targetId = undefined
          permission = '1'
          open = true
        }
      "
      >{{ isApp ? '添加授权' : '添加应用授权' }}</NButton
    >
  </div>
  <QueryState
    :loading="grants.isPending.value"
    :error="grants.error.value || resources.error.value"
    :empty="!grants.data.value?.length"
    @retry="
      () => {
        grants.refetch()
        resources.refetch()
      }
    "
    ><div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{{ isApp ? '知识库' : '应用' }}</th>
            <th>权限</th>
            <th>状态</th>
            <th>授权时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in grants.data.value?.slice((page - 1) * 10, page * 10)" :key="g.id">
            <td>
              <RouterLink
                :to="`${isApp ? '/knowledge-bases' : '/applications'}/${isApp ? g.knowledgeBaseId : g.applicationId}`"
                >{{
                  names.get(isApp ? g.knowledgeBaseId : g.applicationId) ||
                  `#${isApp ? g.knowledgeBaseId : g.applicationId}`
                }}</RouterLink
              >
            </td>
            <td>{{ permissionLabel(g.permission) }}</td>
            <td><StatusTag :status="g.status" /></td>
            <td>{{ formatDateTime(g.grantedAt) }}</td>
            <td>
              <div class="actions">
                <NButton
                  size="small"
                  @click="
                    () => {
                      targetId = isApp ? g.knowledgeBaseId : g.applicationId
                      permission = g.permission
                      open = true
                    }
                  "
                  >修改</NButton
                ><NButton
                  v-if="g.status === '1'"
                  size="small"
                  type="error"
                  quaternary
                  @click="
                    confirm('撤销授权', '撤销后应用将不能再访问此知识库。', () =>
                      api.revokeGrant(tenantId, g.applicationId, g.knowledgeBaseId),
                    )
                  "
                  >撤销</NButton
                >
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <NPagination
      v-model:page="page"
      :page-size="10"
      :item-count="grants.data.value?.length || 0"
      class="pagination" /></QueryState
  ><FormModal
    v-if="open"
    :title="isApp ? '配置知识库授权' : '配置应用授权'"
    submit-text="保存授权"
    :busy="busy"
    @close="open = false"
    @submit="save"
    ><label class="field"
      >{{ isApp ? '知识库' : '应用'
      }}<select v-model="targetId" :aria-label="isApp ? '知识库' : '应用'" required>
        <option :value="undefined" disabled>请选择</option>
        <option v-for="r in resources.data.value" :key="r.id" :value="r.id">
          {{ r.name }} (#{{ r.id }})
        </option>
      </select></label
    ><label class="field"
      >权限<select v-model="permission" aria-label="权限">
        <option v-for="p in permissions" :key="p.value" :value="p.value">{{ p.label }}</option>
      </select></label
    ></FormModal
  >
</template>
