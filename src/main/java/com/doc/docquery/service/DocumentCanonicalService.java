package com.doc.docquery.service;

import com.doc.docquery.entity.DocumentCanonicalArtifactEntity;

/** 为一个可信 DocumentVersion 幂等生成或复用 canonical JSONL 清单。 */
public interface DocumentCanonicalService {

    /** 成功时只返回已写入、读回校验且已登记的派生对象清单。 */
    DocumentCanonicalArtifactEntity ensureCanonical(long documentVersionId);
}
