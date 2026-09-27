<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { NButton, NAlert, NPagination } from 'naive-ui'
import { api, collectAllPages } from '../api/client'
import { useIds, useAction, validId, validatePassword } from '../composables/common'
import type { TenantAdministrator } from '../domain/types'
import { formatDateTime } from '../domain/display'
import PageHeader from '../components/PageHeader.vue'
import QueryState from '../components/QueryState.vue'
import FormModal from '../components/FormModal.vue'
import StatusTag from '../components/StatusTag.vue'
const { id } = useIds()
const { busy, run, confirm } = useAction()
const tenant = useQuery({
  queryKey: ['tenant', id],
  queryFn: () => api.getTenant(id.value),
  enabled: computed(() => validId(id.value)),
})
const admins = useQuery({
  queryKey: ['administrators', id],
  queryFn: () => collectAllPages((p, s) => api.listTenantAdministrators(id.value, p, s)),
  enabled: tenant.isSuccess,
})
const count = computed(() => admins.data.value?.filter((x) => x.status === '1').length || 0)
const page = ref(1)
const modal = ref<'edit' | 'create' | 'reset' | ''>('')
const target = ref<TenantAdministrator>()
const form = reactive({ name: '', loginName: '', password: '' })
function close() {
  modal.value = ''
  form.password = ''
  form.loginName = ''
  target.value = undefined
}
function edit() {
  form.name = tenant.data.value!.name
  modal.value = 'edit'
}
function submit() {
  void run(
    async () => {
      if (modal.value === 'edit') {
        if (!form.name.trim()) throw new Error('请输入名称')
        await api.updateTenant(id.value, { name: form.name.trim() })
      } else {
        validatePassword(form.password)
        if (modal.value === 'create')
          await api.createTenantAdministrator(id.value, {
            loginName: form.loginName,
            password: form.password,
          })
        else await api.resetTenantAdministratorPassword(id.value, target.value!.id, form.password)
      }
    },
    '已保存',
    close,
  )
}
</script>
<template>
  <QueryState
    :loading="tenant.isPending.value && validId(id)"
    :error="!validId(id) ? new Error('无效租户 ID') : tenant.error.value"
    @retry="tenant.refetch()"
    ><template v-if="tenant.data.value"
      ><PageHeader
        :title="tenant.data.value.name"
        :description="`Tenant ID：${id}`"
        back="/tenants"
        back-label="租户管理"
        ><NButton @click="edit">修改名称</NButton
        ><NButton
          :loading="busy"
          @click="
            confirm(
              tenant.data.value.status === '1' ? '停用租户' : '启用租户',
              '停用后将阻止管理员登录及应用访问。',
              () => api.updateTenant(id, { status: tenant.data.value!.status === '1' ? '2' : '1' }),
            )
          "
          >{{ tenant.data.value.status === '1' ? '停用租户' : '启用租户' }}</NButton
        ></PageHeader
      ><NAlert v-if="tenant.data.value.status === '2'" type="warning">租户已停用</NAlert>
      <section class="panel summary">
        <StatusTag :status="tenant.data.value.status" /><span
          >创建于 {{ formatDateTime(tenant.data.value.createdAt) }}</span
        ><span>更新于 {{ formatDateTime(tenant.data.value.updatedAt) }}</span>
      </section>
      <section class="panel">
        <div class="section-heading">
          <h2>租户管理员</h2>
          <NButton type="primary" @click="modal = 'create'">新增管理员</NButton>
        </div>
        <QueryState
          :loading="admins.isPending.value"
          :error="admins.error.value"
          :empty="!admins.data.value?.length"
          @retry="admins.refetch()"
          ><div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>管理员账号</th>
                  <th>状态</th>
                  <th>创建时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="admin in admins.data.value?.slice((page - 1) * 10, page * 10)"
                  :key="admin.id"
                >
                  <td>
                    {{ admin.loginName }}<small>Administrator ID: {{ admin.id }}</small>
                  </td>
                  <td><StatusTag :status="admin.status" /></td>
                  <td>{{ formatDateTime(admin.createdAt) }}</td>
                  <td>
                    <div class="actions">
                      <NButton
                        size="small"
                        @click="
                          () => {
                            target = admin
                            modal = 'reset'
                          }
                        "
                        >重置密码</NButton
                      ><NButton
                        size="small"
                        :disabled="admin.status === '1' && count <= 1"
                        :title="
                          admin.status === '1' && count <= 1 ? '必须至少保留一个有效租户管理员' : ''
                        "
                        @click="
                          confirm(
                            admin.status === '1' ? '停用管理员' : '启用管理员',
                            '停用后该账号的已有会话将失效。',
                            () =>
                              api.updateTenantAdministrator(id, admin.id, {
                                status: admin.status === '1' ? '2' : '1',
                              }),
                          )
                        "
                        >{{ admin.status === '1' ? '停用' : '启用' }}</NButton
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
            :item-count="admins.data.value?.length || 0"
            class="pagination"
        /></QueryState></section></template></QueryState
  ><FormModal
    v-if="modal"
    :title="
      modal === 'edit'
        ? '修改租户名称'
        : modal === 'create'
          ? '新增租户管理员'
          : `重置密码：${target?.loginName}`
    "
    :submit-text="modal === 'create' ? '创建管理员' : modal === 'reset' ? '设置新密码' : '保存'"
    :busy="busy"
    @close="close"
    @submit="submit"
    ><label v-if="modal === 'edit'" class="field"
      >租户名称<input v-model="form.name" aria-label="租户名称" required maxlength="200" /></label
    ><template v-else
      ><label v-if="modal === 'create'" class="field"
        >管理员登录名<input
          v-model="form.loginName"
          aria-label="管理员登录名"
          required
          pattern="[a-z0-9][a-z0-9._-]{2,63}"
          autocomplete="off" /></label
      ><NAlert v-else type="warning">重置密码后原有会话失效。</NAlert
      ><label class="field"
        >{{ modal === 'create' ? '初始密码' : '新密码'
        }}<input
          v-model="form.password"
          :aria-label="modal === 'create' ? '初始密码' : '新密码'"
          required
          type="password"
          minlength="12"
          autocomplete="new-password"
        /><small>至少 12 个字符，最多 72 UTF-8 字节</small></label
      ></template
    ></FormModal
  >
</template>
