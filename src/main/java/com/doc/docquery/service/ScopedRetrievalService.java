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

    /**
     * Answer Search 使用的内部检索入口。
     *
     * <p>默认保持既有 Retrieve 排序；实现可以在不改变公开 `/retrieve` 契约的前提下，
     * 为 Agent 构造覆盖面更均衡的候选池。</p>
     */
    default RetrieveResponseVO retrieveForAnswer(
            QueryAccessContext context,
            RetrieveRequestDTO request
    ) {
        return retrieve(context, request);
    }

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
