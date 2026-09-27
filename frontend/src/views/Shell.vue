<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { NButton, NDrawer, NDrawerContent, NDropdown } from 'naive-ui'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { useUi } from '../stores/ui'
import { api } from '../api/client'
import { roleLabel } from '../domain/display'
import { useAction } from '../composables/common'
const auth = useAuth()
const ui = useUi()
const route = useRoute()
const router = useRouter()
const { run } = useAction()
const mobile = ref(window.innerWidth < 1024)
const resize = () => {
  mobile.value = window.innerWidth < 1024
}
onMounted(() => window.addEventListener('resize', resize))
onUnmounted(() => window.removeEventListener('resize', resize))
const tenant = useQuery({
  queryKey: ['tenant', auth.admin?.tenantId],
  queryFn: () => api.getTenant(auth.admin!.tenantId!),
  enabled: auth.admin?.role === '2',
})
const links = computed(() =>
  auth.admin?.role === '1'
    ? [
        { to: '/tenants', label: '租户管理', icon: '▦' },
        { to: '/guide', label: '使用说明', icon: '◫' },
      ]
    : [
        { to: '/knowledge-bases', label: '知识库管理', icon: '▤' },
        { to: '/knowledge-chat', label: '知识库问答', icon: '◌' },
        { to: '/applications', label: '应用管理', icon: '▦' },
        { to: '/api-playground', label: 'API 调试', icon: '⌘' },
        { to: '/query-audits', label: '查询审计', icon: '≡' },
        { to: '/guide', label: '使用说明', icon: '◫' },
      ],
)
async function logout() {
  await run(() => auth.logout(), '已退出登录')
  await router.replace('/login')
}
</script>
<template>
  <div class="workspace" :class="{ collapsed: ui.collapsed }">
    <aside v-if="!mobile" class="sidebar">
      <RouterLink to="/" class="brand"
        ><span class="brand-mark">D</span><span v-if="!ui.collapsed">DocQuery</span></RouterLink
      >
      <p v-if="!ui.collapsed" class="nav-caption">工作空间</p>
      <nav aria-label="主导航">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          :title="link.label"
          :class="{ active: route.path.startsWith(link.to) }"
          ><span class="nav-icon">{{ link.icon }}</span
          ><span v-if="!ui.collapsed">{{ link.label }}</span></RouterLink
        >
      </nav>
      <div class="sidebar-bottom">
        <span class="scope-dot"></span
        ><span v-if="!ui.collapsed">{{
          auth.admin?.role === '1' ? '平台管理空间' : '租户工作空间'
        }}</span>
      </div>
    </aside>
    <div class="workspace-main">
      <header class="topbar">
        <div class="actions">
          <NButton
            quaternary
            aria-label="切换导航"
            @click="mobile ? (ui.mobileMenu = true) : (ui.collapsed = !ui.collapsed)"
            >☰</NButton
          ><span class="muted">工作空间</span><span class="muted">/</span
          ><strong>{{ route.meta.title || 'DocQuery' }}</strong>
        </div>
        <NDropdown :options="[{ label: '退出登录', key: 'logout' }]" @select="logout"
          ><NButton quaternary
            ><span class="avatar">{{ auth.admin?.loginName.slice(0, 1).toUpperCase() }}</span
            ><span class="user-name">{{ auth.admin?.loginName }}</span></NButton
          ></NDropdown
        >
      </header>
      <main class="page-content">
        <div class="context-line">
          <span class="scope-dot"></span
          >{{
            tenant.data.value?.name ||
            (auth.admin?.role === '1' ? '全平台租户' : `租户 #${auth.admin?.tenantId}`)
          }}<span class="context-role">{{ auth.admin && roleLabel(auth.admin.role) }}</span>
        </div>
        <RouterView v-slot="{ Component }"
          ><component :is="Component" :key="route.path"
        /></RouterView>
        <footer class="page-footer">DocQuery <span>让知识触手可及</span></footer>
      </main>
    </div>
    <NDrawer v-model:show="ui.mobileMenu" :width="280" placement="left"
      ><NDrawerContent title="DocQuery"
        ><nav class="mobile-nav" aria-label="主导航">
          <RouterLink
            v-for="link in links"
            :key="link.to"
            :to="link.to"
            @click="ui.mobileMenu = false"
            >{{ link.label }}</RouterLink
          >
        </nav></NDrawerContent
      ></NDrawer
    >
  </div>
</template>
