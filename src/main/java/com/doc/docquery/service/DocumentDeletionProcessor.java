package com.doc.docquery.service;

import com.doc.docquery.entity.DocumentDeletionJobEntity;
import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.messaging.DocumentDeletionMessage;

/** 已领取租约的整个逻辑文档内容清理端口。 */
public interface DocumentDeletionProcessor {

    void process(Context context);

    record Context(
            DocumentDeletionMessage message,
            DocumentEntity document,
            DocumentDeletionJobEntity job,
            String leaseOwner
    ) {
    }
}
