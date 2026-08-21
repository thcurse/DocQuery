package com.doc.docquery.service;

import com.doc.docquery.dto.RetrieveRequestDTO;
import com.doc.docquery.parser.CanonicalDocument;
import com.doc.docquery.security.ActiveDocumentVersionSnapshot;
import com.doc.docquery.security.QueryAccessContext;
import com.doc.docquery.vo.RetrieveResponseVO;

/**
 * 已授权且已固定 activeVersion 的内部检索核心。
 *
 * <p>调用方必须先取得 {@link QueryAccessContext}。本接口不重新鉴权、不竞争 Redis，
 * 专供 Retrieve 外壳和同一个 ANSWER 执行上下文复用。</p>
 */
public interface ScopedRetrievalService {

    RetrieveResponseVO retrieve(
            QueryAccessContext context,
            RetrieveRequestDTO request
    );

    ScopedDocument loadDocument(
            QueryAccessContext context,
            long documentId
    );

    record ScopedDocument(
            ActiveDocumentVersionSnapshot version,
            CanonicalDocument canonical
    ) {
    }
}
