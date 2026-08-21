package com.doc.docquery.service;

import com.doc.docquery.entity.DocumentSearchProjectionEntity;

/** 将已验证的 canonical/retrieval 派生物幂等投影为 ES 双索引。 */
public interface DocumentSearchProjectionService {

    /** 返回已完整计数校验并登记到 MySQL 的投影验收单。 */
    DocumentSearchProjectionEntity ensureProjection(long documentVersionId);
}
