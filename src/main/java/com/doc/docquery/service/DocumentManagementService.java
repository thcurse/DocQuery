package com.doc.docquery.service;

import com.doc.docquery.dto.DocumentManagementPageQueryDTO;
import com.doc.docquery.dto.ProcessingJobManagementPageQueryDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.DocumentManagementVO;
import com.doc.docquery.vo.DocumentVersionVO;
import com.doc.docquery.vo.PageVO;
import com.doc.docquery.vo.ProcessingJobDetailVO;
import com.doc.docquery.vo.ProcessingJobRetryAcceptedVO;
import com.doc.docquery.vo.ProcessingJobVO;

/** N4.1 文档、版本、处理任务查询和人工重试管理能力。 */
public interface DocumentManagementService {

    PageVO<DocumentManagementVO> listDocuments(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            DocumentManagementPageQueryDTO query
    );

    DocumentManagementVO getDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId
    );

    PageVO<DocumentVersionVO> listVersions(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            int page,
            int size
    );

    PageVO<ProcessingJobVO> listProcessingJobs(
            AdminPrincipal principal,
            long tenantId,
            ProcessingJobManagementPageQueryDTO query
    );

    ProcessingJobDetailVO getProcessingJob(
            AdminPrincipal principal,
            long tenantId,
            long processingJobId
    );

    ProcessingJobRetryAcceptedVO retryProcessingJob(
            AdminPrincipal principal,
            long tenantId,
            long processingJobId,
            String idempotencyKey
    );
}
