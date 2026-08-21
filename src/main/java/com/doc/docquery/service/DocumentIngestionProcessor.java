package com.doc.docquery.service;

import com.doc.docquery.entity.DocumentEntity;
import com.doc.docquery.entity.DocumentVersionEntity;
import com.doc.docquery.entity.ProcessingJobEntity;
import com.doc.docquery.messaging.DocumentProcessingMessage;

/**
 * 完整文档入库处理器端口。
 *
 * <p>N2.5 的生产实现依次保证 canonical、retrieval、ES 双投影及最终激活；
 * Listener 仍需显式开启，避免未配置模型或 ES 时产生外部副作用。</p>
 */
public interface DocumentIngestionProcessor {

    /** 执行一次已领取租约的文档处理；业务成功状态由完整处理器负责提交。 */
    void process(Context context);

    /** 已从 MySQL 交叉校验的可信处理上下文。 */
    record Context(
            DocumentProcessingMessage message,
            DocumentEntity document,
            DocumentVersionEntity version,
            ProcessingJobEntity job,
            String leaseOwner
    ) {
    }
}
