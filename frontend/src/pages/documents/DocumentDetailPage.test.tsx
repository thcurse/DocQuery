import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from 'antd'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { DocumentManagement, DocumentVersion, ProcessingJobSummary } from '../../domain/types'
import { queryClient } from '../../queryClient'
import { DocumentDetailPage } from './DocumentDetailPage'

vi.mock('../../hooks/useTenantId', () => ({ useTenantId: () => 1 }))
vi.mock('../../api/client', () => ({
  api: {
    getDocument: vi.fn(),
    listDocumentVersions: vi.fn(),
    getProcessingJob: vi.fn(),
    rebuildDocument: vi.fn(),
    uploadDocumentVersion: vi.fn(),
  },
}))

const timestamp = '2026-09-07T00:00:00Z'
let versionCount: number
let processing: boolean

function processingJob(versionNo: number): ProcessingJobSummary {
  return {
    processingJobId: 5000 + versionNo,
    attemptNo: 1,
    status: processing && versionNo === versionCount ? '2' : '3',
    failureCode: null,
    failureMessage: null,
    failureRetryable: false,
    startedAt: timestamp,
    finishedAt: timestamp,
    createdAt: timestamp,
    updatedAt: timestamp,
  }
}

function version(versionNo: number): DocumentVersion {
  return {
    documentVersionId: 1000 + versionNo,
    versionNo,
    status: processing && versionNo === versionCount ? '1' : '2',
    active: versionNo === versionCount - (processing ? 1 : 0),
    sourceFormat: 'TXT',
    originalFilename: `revision-${versionNo}.txt`,
    sourceSizeBytes: 128,
    failureCode: null,
    failureMessage: null,
    failureRetryable: false,
    readyAt: timestamp,
    failedAt: null,
    contentDeletedAt: null,
    createdAt: timestamp,
    updatedAt: timestamp,
    latestProcessingJob: processingJob(versionNo),
  }
}

function documentDetail(documentId: number): DocumentManagement {
  return {
    documentId,
    knowledgeBaseId: 2,
    name: `文档 ${documentId}`,
    documentStatus: '1',
    activeVersion: version(versionCount - (processing ? 1 : 0)),
    latestVersion: version(versionCount),
    deletionRequestedAt: null,
    deletedAt: null,
    latestDeletionJob: null,
    createdAt: timestamp,
    updatedAt: timestamp,
  }
}

function acceptNewVersion() {
  versionCount++
  processing = true
  return Promise.resolve({
    documentId: 3,
    documentVersionId: 1000 + versionCount,
    versionNo: versionCount,
    processingJobId: 5000 + versionCount,
    documentStatus: '1' as const,
    versionStatus: '1' as const,
    jobStatus: '1' as const,
    acceptedAt: timestamp,
  })
}

function renderPage() {
  const router = createMemoryRouter([
    { path: '/knowledge-bases/:knowledgeBaseId/documents/:documentId', element: <DocumentDetailPage /> },
  ], { initialEntries: ['/knowledge-bases/2/documents/3'] })
  render(<QueryClientProvider client={queryClient}><App><RouterProvider router={router} /></App></QueryClientProvider>)
  return router
}

beforeEach(() => {
  vi.clearAllMocks()
  queryClient.clear()
  versionCount = 125
  processing = false
  vi.mocked(api.getDocument).mockImplementation(async (_tenantId, _knowledgeBaseId, documentId) => documentDetail(documentId))
  vi.mocked(api.listDocumentVersions).mockImplementation(async (_tenantId, _knowledgeBaseId, _documentId, page, size) => ({
    items: Array.from({ length: versionCount }, (_, index) => version(versionCount - index)).slice(page * size, (page + 1) * size),
    page,
    size,
    total: versionCount,
  }))
  vi.mocked(api.getProcessingJob).mockImplementation(async (_tenantId, processingJobId) => {
    const versionNo = processingJobId - 5000
    return {
      job: {
        ...processingJob(versionNo),
        knowledgeBaseId: 2,
        knowledgeBaseName: '知识库',
        documentId: 3,
        documentName: '文档 3',
        documentVersionId: 1000 + versionNo,
        versionNo,
        jobType: 'DOCUMENT_PROCESSING',
      },
      attemptHistory: [processingJob(versionNo)],
    }
  })
  vi.mocked(api.rebuildDocument).mockImplementation(acceptNewVersion)
  vi.mocked(api.uploadDocumentVersion).mockImplementation(acceptNewVersion)
})

describe('DocumentDetailPage 版本历史分页', () => {
  it('可访问第 100 条以后的版本及对应任务，切换文档会重置页码和任务抽屉', async () => {
    const user = userEvent.setup()
    const router = renderPage()

    expect(await screen.findByText('共 125 个版本')).toBeInTheDocument()
    expect(api.listDocumentVersions).toHaveBeenCalledWith(1, 2, 3, 0, 20)
    await user.click(screen.getByTitle('7'))
    expect(await screen.findByText(/revision-1\.txt/)).toBeInTheDocument()
    expect(screen.queryByText(/revision-125\.txt/)).not.toBeInTheDocument()
    expect(api.listDocumentVersions).toHaveBeenCalledWith(1, 2, 3, 6, 20)

    await user.click(screen.getByRole('button', { name: /#5001/ }))
    expect(await screen.findByText('DOCUMENT_PROCESSING')).toBeInTheDocument()
    expect(api.getProcessingJob).toHaveBeenCalledWith(1, 5001)
    expect(screen.getByText('处理任务 #5001')).toBeInTheDocument()

    await act(() => router.navigate('/knowledge-bases/2/documents/4'))
    expect(await screen.findByText(/revision-125\.txt/)).toBeInTheDocument()
    expect(screen.queryByText('处理任务 #5001')).not.toBeInTheDocument()
    expect(api.listDocumentVersions).toHaveBeenCalledWith(1, 2, 4, 0, 20)
    expect(vi.mocked(api.listDocumentVersions).mock.calls.some((call) => call[2] === 4 && call[3] !== 0)).toBe(false)
  })

  it.each(['上传', '重建'] as const)('%s 新版本后回到第一页，并持续刷新到处理完成', async (action) => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('共 125 个版本')
    await user.click(screen.getByTitle('7'))
    await screen.findByText(/revision-1\.txt/)

    if (action === '重建') {
      await user.click(screen.getByRole('button', { name: /重\s*建$/ }))
      await user.click(await screen.findByRole('button', { name: '确认重建' }))
    } else {
      await user.click(screen.getByRole('button', { name: /上传新版本/ }))
      const fileInput = document.querySelector<HTMLInputElement>('input[type="file"]')!
      await user.upload(fileInput, new File(['new content'], 'new-version.txt', { type: 'text/plain' }))
      await user.click(screen.getByRole('button', { name: '开始上传' }))
    }

    const file = await screen.findByText(/revision-126\.txt/)
    expect(screen.getByText('共 126 个版本')).toBeInTheDocument()
    expect(screen.getByTitle('1')).toHaveClass('ant-pagination-item-active')
    expect(screen.queryByText(/revision-1\.txt/)).not.toBeInTheDocument()
    expect(within(file.closest('tr')!).getByText('处理中')).toBeInTheDocument()

    processing = false
    await waitFor(() => {
      const latestRow = screen.getByText(/revision-126\.txt/).closest('tr')!
      expect(within(latestRow).getByText('已就绪')).toBeInTheDocument()
      expect(within(latestRow).getByText('当前生效')).toBeInTheDocument()
    }, { timeout: 5000 })
  }, 12000)
})
