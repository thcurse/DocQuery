import { createHashRouter } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
import { RoleGate } from './auth/RoleGate'
import { AppShell } from './layout/AppShell'
import { HomeRedirect } from './pages/HomeRedirect'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'

export const router = createHashRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        path: '/',
        element: <AppShell />,
        children: [
          { index: true, element: <HomeRedirect /> },
          {
            path: 'tenants',
            lazy: async () => {
              const { TenantListPage } = await import('./pages/tenants/TenantListPage')
              return { Component: () => <RoleGate role="1"><TenantListPage /></RoleGate> }
            },
          },
          {
            path: 'tenants/:tenantId',
            lazy: async () => {
              const { TenantDetailPage } = await import('./pages/tenants/TenantDetailPage')
              return { Component: () => <RoleGate role="1"><TenantDetailPage /></RoleGate> }
            },
          },
          {
            path: 'applications',
            lazy: async () => {
              const { ApplicationListPage } = await import('./pages/applications/ApplicationListPage')
              return { Component: () => <RoleGate role="2"><ApplicationListPage /></RoleGate> }
            },
          },
          {
            path: 'applications/:applicationId',
            lazy: async () => {
              const { ApplicationDetailPage } = await import('./pages/applications/ApplicationDetailPage')
              return { Component: () => <RoleGate role="2"><ApplicationDetailPage /></RoleGate> }
            },
          },
          {
            path: 'knowledge-bases',
            lazy: async () => {
              const { KnowledgeBaseListPage } = await import('./pages/knowledgeBases/KnowledgeBaseListPage')
              return { Component: () => <RoleGate role="2"><KnowledgeBaseListPage /></RoleGate> }
            },
          },
          {
            path: 'knowledge-bases/:knowledgeBaseId',
            lazy: async () => {
              const { KnowledgeBaseDetailPage } = await import('./pages/knowledgeBases/KnowledgeBaseDetailPage')
              return { Component: () => <RoleGate role="2"><KnowledgeBaseDetailPage /></RoleGate> }
            },
          },
          {
            path: 'knowledge-bases/:knowledgeBaseId/documents/:documentId',
            lazy: async () => {
              const { DocumentDetailPage } = await import('./pages/documents/DocumentDetailPage')
              return { Component: () => <RoleGate role="2"><DocumentDetailPage /></RoleGate> }
            },
          },
          {
            path: 'knowledge-chat',
            lazy: async () => {
              const { KnowledgeChatPage } = await import('./pages/query/KnowledgeChatPage')
              return { Component: () => <RoleGate role="2"><KnowledgeChatPage /></RoleGate> }
            },
          },
          {
            path: 'api-playground',
            lazy: async () => {
              const { ApiPlaygroundPage } = await import('./pages/query/ApiPlaygroundPage')
              return { Component: () => <RoleGate role="2"><ApiPlaygroundPage /></RoleGate> }
            },
          },
          {
            path: 'query-audits',
            lazy: async () => {
              const { QueryAuditListPage } = await import('./pages/audits/QueryAuditListPage')
              return { Component: () => <RoleGate role="2"><QueryAuditListPage /></RoleGate> }
            },
          },
          {
            path: 'query-audits/:auditId',
            lazy: async () => {
              const { QueryAuditDetailPage } = await import('./pages/audits/QueryAuditDetailPage')
              return { Component: () => <RoleGate role="2"><QueryAuditDetailPage /></RoleGate> }
            },
          },
          {
            path: 'guide',
            lazy: async () => {
              const { UsageGuidePage } = await import('./pages/guide/UsageGuidePage')
              return { Component: UsageGuidePage }
            },
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
])
