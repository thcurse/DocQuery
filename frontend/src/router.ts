import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuth } from './stores/auth'
export const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: () => import('./views/Login.vue') },
    {
      path: '/',
      component: () => import('./views/Shell.vue'),
      children: [
        { path: '', component: () => import('./views/HomeRedirect.vue') },
        {
          path: 'tenants',
          component: () => import('./views/ResourceList.vue'),
          props: { kind: 'tenant' },
          meta: { role: '1', title: '租户管理' },
        },
        {
          path: 'tenants/:id',
          component: () => import('./views/TenantDetail.vue'),
          meta: { role: '1', title: '租户详情' },
        },
        {
          path: 'applications',
          component: () => import('./views/ResourceList.vue'),
          props: { kind: 'application' },
          meta: { role: '2', title: '应用管理' },
        },
        {
          path: 'applications/:id',
          component: () => import('./views/ResourceDetail.vue'),
          props: { kind: 'application' },
          meta: { role: '2', title: '应用详情' },
        },
        {
          path: 'knowledge-bases',
          component: () => import('./views/ResourceList.vue'),
          props: { kind: 'knowledgeBase' },
          meta: { role: '2', title: '知识库管理' },
        },
        {
          path: 'knowledge-bases/:id',
          component: () => import('./views/ResourceDetail.vue'),
          props: { kind: 'knowledgeBase' },
          meta: { role: '2', title: '知识库详情' },
        },
        {
          path: 'knowledge-bases/:kbId/documents/:id',
          component: () => import('./views/DocumentDetail.vue'),
          meta: { role: '2', title: '文档详情' },
        },
        {
          path: 'knowledge-chat',
          component: () => import('./views/QueryWorkspace.vue'),
          props: { chat: true },
          meta: { role: '2', title: '知识库问答' },
        },
        {
          path: 'api-playground',
          component: () => import('./views/QueryWorkspace.vue'),
          props: { chat: false },
          meta: { role: '2', title: 'API 调试' },
        },
        {
          path: 'query-audits',
          component: () => import('./views/Audits.vue'),
          meta: { role: '2', title: '查询审计' },
        },
        {
          path: 'query-audits/:id',
          component: () => import('./views/AuditDetail.vue'),
          meta: { role: '2', title: '审计详情' },
        },
        {
          path: 'guide',
          component: () => import('./views/Guide.vue'),
          meta: { title: '使用说明' },
        },
        {
          path: 'forbidden',
          component: () => import('./views/NotFound.vue'),
          props: { forbidden: true },
          meta: { title: '无权访问' },
        },
        { path: ':pathMatch(.*)*', component: () => import('./views/NotFound.vue') },
      ],
    },
  ],
})
router.beforeEach(async (to) => {
  const auth = useAuth()
  if (to.path === '/login') return true
  try {
    await auth.restore()
  } catch {
    return '/login?restore=failed'
  }
  if (!auth.admin) return '/login'
  if (to.meta.role && to.meta.role !== auth.admin.role) return '/forbidden'
})
