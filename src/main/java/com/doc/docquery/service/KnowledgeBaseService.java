package com.doc.docquery.service;

import com.doc.docquery.dto.CreateKnowledgeBaseDTO;
import com.doc.docquery.dto.PageQueryDTO;
import com.doc.docquery.dto.UpdateKnowledgeBaseDTO;
import com.doc.docquery.security.AdminPrincipal;
import com.doc.docquery.vo.KnowledgeBaseVO;
import com.doc.docquery.vo.PageVO;

/** 租户知识库元数据管理业务边界。 */
public interface KnowledgeBaseService {

    /** 在当前租户内创建知识库。 */
    KnowledgeBaseVO createKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            CreateKnowledgeBaseDTO dto
    );

    /** 分页列出当前租户的知识库。 */
    PageVO<KnowledgeBaseVO> listKnowledgeBases(
            AdminPrincipal principal,
            long tenantId,
            PageQueryDTO query
    );

    /** 读取当前租户内的单个知识库。 */
    KnowledgeBaseVO getKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId
    );

    /** 修改知识库可变元数据及启停状态。 */
    KnowledgeBaseVO updateKnowledgeBase(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            UpdateKnowledgeBaseDTO dto
    );
}
