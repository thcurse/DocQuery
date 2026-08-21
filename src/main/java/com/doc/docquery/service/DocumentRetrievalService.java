package com.doc.docquery.service;

import com.doc.docquery.entity.DocumentRetrievalArtifactEntity;

/** 为一个已有 canonical 对象的版本幂等生成 N2.4 检索派生物。 */
public interface DocumentRetrievalService {

    DocumentRetrievalArtifactEntity ensureRetrieval(long documentVersionId);
}
